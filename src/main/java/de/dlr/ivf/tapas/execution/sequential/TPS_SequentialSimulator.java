package de.dlr.ivf.tapas.execution.sequential;

import com.lmax.disruptor.*;
import com.lmax.disruptor.util.DaemonThreadFactory;
import de.dlr.ivf.tapas.execution.sequential.action.TripPersistenceAction;
import de.dlr.ivf.tapas.execution.sequential.statemachine.HouseholdBasedStateMachineController;
import de.dlr.ivf.tapas.log.TPS_Logger;
import de.dlr.ivf.tapas.log.TPS_LoggingInterface;
import de.dlr.ivf.tapas.mode.TazBasedCarSharingDelegator;
import de.dlr.ivf.tapas.persistence.db.TPS_DB_IOManager;
import de.dlr.ivf.tapas.persistence.db.TPS_PipedDbWriter;
import de.dlr.ivf.tapas.persistence.db.TPS_TripWriter;
import de.dlr.ivf.tapas.person.TPS_Car;
import de.dlr.ivf.tapas.person.TPS_Household;
import de.dlr.ivf.tapas.plan.TPS_Plan;
import de.dlr.ivf.tapas.execution.sequential.event.*;
import de.dlr.ivf.tapas.execution.sequential.statemachine.TPS_StateMachine;
import de.dlr.ivf.tapas.execution.sequential.statemachine.TPS_StateMachineFactory;
import de.dlr.ivf.tapas.util.FuncUtils;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

/**
 * This should run inside its own thread. Upon construction the simulator will set up a {@link TPS_StateMachine}
 * for each plan passed to the constructor and determines the first {@link TPS_PlanEvent}.
 *
 * When the {@link TPS_SequentialSimulator} is started, a single producer of {@link TPS_StateMachineEvent}s
 * and a {@link WorkerPool} of {@link TPS_StateMachineHandler}s as consumers are set up around a {@link RingBuffer}.
 * It is recommended that the count of {@link TPS_StateMachineHandler} does not exceed the count of PHYSICAL cpu cores.
 * Best results in performance have been achieved with "physical core count" - 2.
 *
 * During iteration, every simulation time step is wrapped into a {@link TPS_PlanEvent} of type {@link TPS_EventType#SIMULATION_STEP}
 * and passed to every {@link TPS_StateMachine}. If a state machine will handle the event, it is wrapped into a
 * {@link TPS_StateMachineEvent} and put onto the {@link RingBuffer} for transition.
 *
 * After each iteration (simulation time stamp), the {@link TPS_SequentialSimulator} will wait until all {@link TPS_StateMachineHandler}s
 * have finished working on their dedicated events. When all handlers are done, a new {@link TPS_PlanEvent} with an incremented
 * simulation time stamp is generated.
 *
 * When an exception occurs during computations it will be handled by the {@link StateMachineEventExceptionHandler}.
 *
 */

public class TPS_SequentialSimulator implements Runnable{
    private int worker_count;
    private int buffer_size;
    private TPS_DB_IOManager pm;

    private AtomicInteger simulation_time_stamp = new AtomicInteger(0);
    private TPS_PlanEvent next_simulation_event;
    private TPS_PipedDbWriter writer;
    private List<HouseholdBasedStateMachineController> state_machine_controllers;
    private TPS_StateMachineHandler[] workers;
    private Map<Integer, TazBasedCarSharingDelegator> carsharing_mediators;

    /**
     * Initializes all {@link TPS_StateMachine}s and the first simulation time event.
     * @param worker_count the number of {@link TPS_StateMachineHandler}s. It is recommended that the worker count does
     *                     not exceed the number of PHYSICAL cores in the cpu.
     * @param pm the persistence manager
     * @param writer that handles persisting the data
     * @param buffer_size of the {@link RingBuffer}. Must be a power of 2.
     */
    public TPS_SequentialSimulator(List<HouseholdBasedStateMachineController> state_machine_controllers, int worker_count, TPS_DB_IOManager pm, TPS_TripWriter writer, int buffer_size, TPS_PlanEvent start_event){

        this.worker_count = worker_count;
        this.buffer_size = buffer_size;
        this.workers = new TPS_StateMachineHandler[this.worker_count];
        this.pm = pm;
        this.writer = (TPS_PipedDbWriter) writer;
        this.state_machine_controllers = state_machine_controllers;
        this.next_simulation_event = start_event;
        this.simulation_time_stamp.set(next_simulation_event.getData());
    }

    /**
     * Extracts the start time for the simulation and generates the first {@link TPS_PlanEvent}.
     * @param plans containing all {@link de.dlr.ivf.tapas.scheme.TPS_SchemePart}s and their {@link de.dlr.ivf.tapas.scheme.TPS_Episode}s
     * @return a {@link TPS_PlanEvent} of type {@link TPS_EventType#SIMULATION_STEP} where its payload is equal to the time
     *         the first person is leaving the house.
     */

    private TPS_PlanEvent initAndGetFirstSimulationTimeEvent(List<TPS_Plan> plans){

        TPS_Logger.log(TPS_LoggingInterface.HierarchyLogLevel.THREAD, TPS_LoggingInterface.SeverenceLogLevel.INFO, "Initializing first simulation event...");

        AtomicInteger simulation_start_time = new AtomicInteger(0);

        plans.stream().parallel()
                .mapToInt(plan -> plan.getScheme().getSchemeParts().get(0).getFirstEpisode().getOriginalDuration())
                .min()
                .ifPresentOrElse( i -> simulation_start_time.set(FuncUtils.secondsToRoundedMinutes.apply(i)), () -> {throw new RuntimeException();});

        TPS_PlanEvent first_simulation_event = new TPS_PlanEvent(TPS_EventType.SIMULATION_STEP,  simulation_start_time.get());

        TPS_Logger.log(TPS_LoggingInterface.HierarchyLogLevel.THREAD, TPS_LoggingInterface.SeverenceLogLevel.INFO, "First simulation event is of type: "+first_simulation_event.getEventType()+" at time: "+first_simulation_event.getData());

        return first_simulation_event;
    }

//    /**
//     * Creates a {@link TPS_StateMachine} with a set of states representing the behaviour for every {@link de.dlr.ivf.tapas.scheme.TPS_Episode}
//     * inside a {@link TPS_Plan}.
//     * @param plans that need to be represented as a {@link TPS_StateMachine}
//     * @param writer for the {@link TripPersistenceAction}s
//     * @return a list of {@link TPS_StateMachine}s
//     */
//
//    private List<TPS_StateMachine> createAndGetStateMachines(List<TPS_Plan> plans, TPS_TripWriter writer) {
//
//        var plan_count = plans.size();
//        var state_machines = new ArrayList<TPS_StateMachine>(plan_count);
//
//        TPS_Logger.log(TPS_LoggingInterface.HierarchyLogLevel.THREAD, TPS_LoggingInterface.SeverenceLogLevel.INFO, "Generating "+plan_count+" state machines...");
//
//        plans.forEach(plan -> state_machines.add(TPS_StateMachineFactory.createStateMachineWithSimpleStates(plan, writer, pm, carsharing_mediators)));
//
//        TPS_Logger.log(TPS_LoggingInterface.HierarchyLogLevel.THREAD, TPS_LoggingInterface.SeverenceLogLevel.INFO, "All state machines ready...");
//
//        return state_machines;
//    }

//    private List<HouseholdBasedStateMachineController> createAndGetHouseholdControllers(List<TPS_Household> households, TPS_TripWriter writer) {
//
//        return households.stream().map(hh -> new HouseholdBasedStateMachineController(hh)).collect(Collectors.toList());
//
//    }

    public void run(){

        //event factory for event pre-allocation on the ring
        EventFactory<TPS_StateMachineEvent> event_factory = TPS_StateMachineEvent::new;

        //set up our event consumers
        IntStream.range(0,worker_count).forEach(i -> workers[i] = new TPS_StateMachineHandler("worker_"+i));

        //set up the ring and worker pool
        RingBuffer<TPS_StateMachineEvent> ring_buffer = RingBuffer.createSingleProducer(event_factory, buffer_size, new BusySpinWaitStrategy());

        StateMachineEventExceptionHandler exceptionHandler = new StateMachineEventExceptionHandler();

        WorkerPool<TPS_StateMachineEvent> worker_pool = new WorkerPool<>(ring_buffer, ring_buffer.newBarrier(), exceptionHandler, workers);
        ring_buffer.addGatingSequences(worker_pool.getWorkerSequences());

        //start the worker pool
        Executor executor = Executors.newCachedThreadPool(DaemonThreadFactory.INSTANCE);
        worker_pool.start(executor);

        var all_finished = false;
        var total_count = 0;
        var sim_start = System.currentTimeMillis();
        var last_written_trip_count = 0;

        //launch the simulation progress update thread
        writer.startSimulationProgressUpdateTask();

        //start the simulation
        while(!all_finished && simulation_time_stamp.get() < 10000){

            //all_finished = true;
            var unfinished = 0;
            var event_count = 0;
            var current_iteration_start_time = System.currentTimeMillis();

            TPS_Logger.log(TPS_LoggingInterface.HierarchyLogLevel.THREAD, TPS_LoggingInterface.SeverenceLogLevel.INFO, "Publishing and consuming events for simulation time: "+simulation_time_stamp.get());


            state_machine_controllers.stream()
                                     .filter(state_machine_controller -> state_machine_controller.willPassEvent(next_simulation_event))
                                     .forEach(state_machine_controller -> publishToRingBuffer(state_machine_controller, ring_buffer));



            //remove finished controllers from the list we iterate over and update some counts
            if(simulation_time_stamp.get() % 5 == 0){
                state_machine_controllers = state_machine_controllers.stream().filter(controller -> controller.getUnfinishedCount() > 0).collect(Collectors.toList());
            }


            TPS_Logger.log(TPS_LoggingInterface.HierarchyLogLevel.THREAD, TPS_LoggingInterface.SeverenceLogLevel.INFO, event_count+" events published for "+unfinished+" unfinished state machines in "+(System.currentTimeMillis()-current_iteration_start_time)+" ms");

            //the position of the last published event
            long cursor = ring_buffer.getCursor();

            //wait until all workers have finished the latest batch
            while(Arrays.stream(worker_pool.getWorkerSequences()).anyMatch(sequence -> sequence.get() < cursor)){
                Thread.onSpinWait();
            }

            var written_trip_count = writer.getWrittenTripCount();
            var trips_in_pipeline_count = writer.getRegisteredTripCount();

            TPS_Logger.log(TPS_LoggingInterface.HierarchyLogLevel.THREAD, TPS_LoggingInterface.SeverenceLogLevel.INFO, event_count+" events handled, "+( written_trip_count - last_written_trip_count)+
                    " trips written  in "+(System.currentTimeMillis()-current_iteration_start_time)/1000+" seconds for simulation time: "+simulation_time_stamp.get()+
                    " ( "+trips_in_pipeline_count+" trips in writer pipeline )");

            last_written_trip_count = written_trip_count;

            next_simulation_event = new TPS_PlanEvent(TPS_EventType.SIMULATION_STEP,  simulation_time_stamp.incrementAndGet());
        }

        TPS_Logger.log(TPS_LoggingInterface.HierarchyLogLevel.THREAD, TPS_LoggingInterface.SeverenceLogLevel.INFO, "Simulation finished! "+total_count+" events handled in "+(System.currentTimeMillis()-sim_start)/60000+" minutes");

        worker_pool.drainAndHalt();
        writer.finish();
    }

    private void publishToRingBuffer(HouseholdBasedStateMachineController state_machine_controller, RingBuffer<TPS_StateMachineEvent> ring_buffer) {

        //get sequence id of the next available event slot
        long sequenceId = ring_buffer.next();

        //get the associated event
        TPS_StateMachineEvent event = ring_buffer.get(sequenceId);

        //set event values
        event.setStateMachineController(state_machine_controller);
        event.setEvent(next_simulation_event);

        //finally publish the event being ready to be picked up by a worker
        ring_buffer.publish(sequenceId);
    }

//    private Map<Integer, TazBasedCarSharingDelegator> createCarSharingMediators(Collection<Integer> taz_ids, TPS_Car random_car){
//        Map<Integer, TazBasedCarSharingDelegator> result_sharing_mediators = new HashMap<>(taz_ids.size());
//
//        taz_ids.forEach(taz_id -> result_sharing_mediators.put(taz_id, new TazBasedCarSharingDelegator(100,taz_ids, random_car)));
//
//        return result_sharing_mediators;
//    }
}
