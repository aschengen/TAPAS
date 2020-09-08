package de.dlr.ivf.tapas.plan.state.action;

import de.dlr.ivf.tapas.constants.TPS_ActivityConstant;
import de.dlr.ivf.tapas.constants.TPS_SettlementSystem;
import de.dlr.ivf.tapas.loc.TPS_Location;
import de.dlr.ivf.tapas.log.TPS_Logger;
import de.dlr.ivf.tapas.log.TPS_LoggingInterface;
import de.dlr.ivf.tapas.mode.TPS_ExtMode;
import de.dlr.ivf.tapas.persistence.TPS_PersistenceManager;
import de.dlr.ivf.tapas.person.TPS_Car;
import de.dlr.ivf.tapas.plan.*;
import de.dlr.ivf.tapas.plan.state.TPS_PlansExecutor;
import de.dlr.ivf.tapas.plan.state.statemachine.TPS_PlanState;
import de.dlr.ivf.tapas.plan.state.event.TPS_PlanEventType;
import de.dlr.ivf.tapas.scheme.TPS_Stay;
import de.dlr.ivf.tapas.scheme.TPS_TourPart;
import de.dlr.ivf.tapas.scheme.TPS_Trip;
import de.dlr.ivf.tapas.util.TPS_AttributeReader;
import de.dlr.ivf.tapas.util.parameters.ParamFlag;
import java.util.Objects;


public class TPS_PlanStateSelectLocationAndModeAction implements TPS_PlanStateAction {

    TPS_TourPart tour_part;
    TPS_Plan plan;
    TPS_Stay departure_stay;
    TPS_Stay arrival_stay;
    TPS_Stay next_fix_stay;
    TPS_PersistenceManager pm;
    TPS_PlanState trip_state;
    TPS_PlanState post_activity_trip_state;
    TPS_PlanState post_trip_activity_state;
    TPS_Trip previous_trip;
    TPS_Trip associated_trip;

    TPS_PlansExecutor executor;


    public TPS_PlanStateSelectLocationAndModeAction(TPS_Plan plan, TPS_TourPart tour_part, TPS_Trip associated_trip, TPS_Stay departure_stay, TPS_Stay arrival_stay, TPS_Stay next_fix_stay, TPS_PersistenceManager pm, TPS_PlanState trip_state, TPS_PlanState post_trip_activity_state, TPS_PlanState post_activity_trip_state, TPS_PlansExecutor executor){
        Objects.requireNonNull(tour_part);
        Objects.requireNonNull(plan);
        Objects.requireNonNull(arrival_stay);
        Objects.requireNonNull(pm);
        Objects.requireNonNull(trip_state);
        Objects.requireNonNull(post_activity_trip_state);
        Objects.requireNonNull(post_trip_activity_state);
        Objects.requireNonNull(departure_stay);
        Objects.requireNonNull(next_fix_stay);
        Objects.requireNonNull(associated_trip);
        Objects.requireNonNull(executor);

        this.tour_part = tour_part;
        this.plan = plan;
        this.arrival_stay = arrival_stay;
        this.pm = pm;
        this.trip_state = trip_state;
        this.post_activity_trip_state = post_activity_trip_state;
        this.post_trip_activity_state = post_trip_activity_state;
        this.departure_stay = departure_stay;
        this.associated_trip = associated_trip;
        this.next_fix_stay = next_fix_stay;
        this.executor = executor;
    }


    @Override
    public void run() {


        // check mobility options
        TPS_PlanningContext pc = plan.getPlanningContext();
        if (tour_part.isCarUsed()) {
            pc.carForThisPlan = tour_part.getCar();
        } else if (!pc.influenceCarUsageInPlan) {
            //check if a car could be used
            if (plan.getPerson().mayDriveACar()) {
                pc.carForThisPlan = TPS_Car.selectCar(plan, tour_part);
            } else {
                pc.carForThisPlan = null;
            }
        }

        if (tour_part.isBikeUsed()) { //was the bike used before?
            pc.isBikeAvailable = true;
        } else if (!pc.influenceBikeUsageInPlan) { // is the bike availability modded outside?
            pc.isBikeAvailable = plan.getPerson().hasBike();
        }
        plan.getAttributes().put(TPS_AttributeReader.TPS_Attribute.PERSON_HAS_BIKE, pc.isBikeAvailable ? 1 : 0);

        if (pc.carForThisPlan == null) {
            plan.getAttributes().put(TPS_AttributeReader.TPS_Attribute.HOUSEHOLD_CARS, 0);
        } else {
            plan.getAttributes().put(TPS_AttributeReader.TPS_Attribute.HOUSEHOLD_CARS, plan.getPerson().getHousehold().getCarNumber());
        }

        plan.getAttributes().put(TPS_AttributeReader.TPS_Attribute.CURRENT_EPISODE_ACTIVITY_CODE_TAPAS,
                arrival_stay.getActCode().getCode(TPS_ActivityConstant.TPS_ActivityCodeType.TAPAS));

        plan.getPlanningContext().pe.getPerson().estimateAccessibilityPreference(arrival_stay,
                pm.getParameters().isTrue(ParamFlag.FLAG_USE_SHOPPING_MOTIVES));

        TPS_LocatedStay currentLocatedStay = plan.getLocatedStay(arrival_stay);
        if (!currentLocatedStay.isLocated()) {
            plan.setCurrentAdaptedEpisode(currentLocatedStay);
            TPS_ActivityConstant currentActCode = arrival_stay.getActCode();
            // Register locations for activities where the location will be used again.
            // Flag for the case of a activity with unique location.
            plan.getPlanningContext().fixLocationAtBase = currentActCode.isFix() && pm.getParameters().isTrue(
                    ParamFlag.FLAG_USE_FIXED_LOCS_ON_BASE) && !plan.getFixLocations().containsKey(currentActCode);

            // when all tour parts are correctly instantiated the else case will never happen, because every
            // tour part starts with a trip. In the current episode file there exist tour parts with no first
            // trip (e.g. shopping in the same building where you live)
            //TPS_Trip previousTrip = null;
            if (!tour_part.isFirst(arrival_stay)) {
                plan.getPlanningContext().previousTrip = tour_part.getPreviousTrip(arrival_stay);
            } else {
                plan.getPlanningContext().previousTrip = new TPS_Trip(-999, TPS_ActivityConstant.DUMMY, -999, 0, plan.getParameters());
            }

            //First execution: fix locations will be set (ELSE branch)
            //Further executions: fix locations are set already, take locations from map (IF branch)
            //Non fix location, i.e. everything except home and work: also ELSE branch
            //E.g.: Provides a work location, when ActCode is working in ELSE
//TODO change this in the original code in the plan class at the selectlocationandmode method
            if (currentActCode.isFix() && plan.getFixLocations().containsKey(currentActCode) &&
                //now we check if the mode is fix and if we can reach the fix location with the fix mode!
                //TODO: check only for restricted cars, but bike could also be fixed and no connection!
                !(plan.getPlanningContext().carForThisPlan != null && // we have a car
                        plan.getPlanningContext().carForThisPlan.isRestricted() && //we have a restricted car
                        plan.getFixLocations().get(currentActCode).getTrafficAnalysisZone()
                                .isRestricted())) // we have a restricted car wanting to go to a restricted area! -> BAD!
                {

                    currentLocatedStay.setLocation(plan.getFixLocations().get(currentActCode));

                if (TPS_Logger.isLogging(TPS_LoggingInterface.HierarchyLogLevel.EPISODE, TPS_LoggingInterface.SeverenceLogLevel.FINE)) {
                    TPS_Logger.log(TPS_LoggingInterface.HierarchyLogLevel.EPISODE, TPS_LoggingInterface.SeverenceLogLevel.FINE,
                            "Set location from fix locations");
                }
            } else {
                currentLocatedStay.selectLocation(plan, plan.getPlanningContext());
                if (currentActCode.isFix()) {
                    plan.getFixLocations().put(currentActCode, plan.getLocatedStay(arrival_stay).getLocation());
                }
            }

            // increment the occupancy of the found location by one
            TPS_Location loc = currentLocatedStay.getLocation();
            //loc.updateOccupancy(1);//fixme reverse this and todo check out the occupancy

            if (currentLocatedStay.getLocation() == null) {
                TPS_Logger.log(TPS_LoggingInterface.SeverenceLogLevel.ERROR, "No Location found!");
            }

            if (TPS_Logger.isLogging(TPS_LoggingInterface.SeverenceLogLevel.DEBUG)) {
                TPS_Logger.log(TPS_LoggingInterface.SeverenceLogLevel.DEBUG,
                        "Selected location (id=" + currentLocatedStay.getLocation().getId() +
                                ") for stay (id=" + currentLocatedStay.getEpisode().getId() + " in TAZ (id=" +
                                currentLocatedStay.getLocation().getTrafficAnalysisZone().getTAZId() +
                                ") in block (id= " +
                                (currentLocatedStay.getLocation().hasBlock() ? currentLocatedStay.getLocation()
                                        .getBlock()
                                        .getId() : -1) +
                                ") via modes " + currentLocatedStay.getModeArr().getName() + "/" +
                                currentLocatedStay.getModeDep().getName());
            }
        }

        // fetch previous and next stay
        if (currentLocatedStay.getModeArr() == null || currentLocatedStay.getModeDep() == null) {
            if (TPS_Logger.isLogging(TPS_LoggingInterface.SeverenceLogLevel.FINE)) {
                TPS_Logger.log(TPS_LoggingInterface.SeverenceLogLevel.FINE,
                        "Start select mode for stay in tour part (id=" + tour_part.getId() + ")");
            }
            //do we have a fixed mode from the previous trip?
            if (tour_part.isFixedModeUsed()) {
                currentLocatedStay.setModeArr(tour_part.getUsedMode());
                currentLocatedStay.setModeDep(tour_part.getUsedMode());
            } else {
                TPS_Location home_location = plan.getLocatedStay(next_fix_stay).getLocation();
                TPS_Car tmpCar = plan.getPlanningContext().carForThisPlan;
                if (tmpCar != null && tmpCar.isRestricted() &&
                        (currentLocatedStay.getLocation().getTrafficAnalysisZone().isRestricted() ||
                                home_location.getTrafficAnalysisZone().isRestricted())) {
                    plan.getPlanningContext().carForThisPlan = null;
                }

                //fixme the focus point is set to the next home stay which defers from the original household based execution implementation
                pm.getModeSet().selectMode(plan, departure_stay, currentLocatedStay, next_fix_stay, plan.getPlanningContext());
                plan.getPlanningContext().carForThisPlan = tmpCar;
                //set the mode and car (if used)
                tour_part.setUsedMode(currentLocatedStay.getModeDep(), plan.getPlanningContext().carForThisPlan);

                //set some variables for the fixed modes
                TPS_ExtMode em = tour_part.getUsedMode();
                plan.usesBike = em.isBikeUsed();
                if (em.isCarUsed()) {
                    plan.usedCars.add(plan.getPlanningContext().carForThisPlan);
                }

                //set variables for fixed modes:
                plan.getPlanningContext().carForThisPlan = tour_part.getCar();
                plan.getPlanningContext().isBikeAvailable = tour_part.isBikeUsed();
                if (plan.getPlanningContext().carForThisPlan == null) {
                    plan.getAttributes().put(TPS_AttributeReader.TPS_Attribute.HOUSEHOLD_CARS, 0);
                } else {
                    plan.getAttributes().put(TPS_AttributeReader.TPS_Attribute.HOUSEHOLD_CARS,
                            plan.getPerson().getHousehold().getCarNumber());
                }
                plan.getAttributes().put(TPS_AttributeReader.TPS_Attribute.PERSON_HAS_BIKE, plan.getPlanningContext().isBikeAvailable ? 1 : 0);
            }
        }

        if (TPS_Logger.isLogging(TPS_LoggingInterface.SeverenceLogLevel.DEBUG)) {
            TPS_Logger.log(TPS_LoggingInterface.SeverenceLogLevel.DEBUG,
                    "Selected mode (id=" + currentLocatedStay.getModeArr() == null ? "NULL" :
                            currentLocatedStay.getModeArr().getName() + ") for stay (id=" +
                                    currentLocatedStay.getEpisode().getId() + " in TAZ (id=" +
                                    currentLocatedStay.getLocation().getTrafficAnalysisZone().getTAZId() +
                                    ") in block (id= " +
                                    (currentLocatedStay.getLocation().hasBlock() ? currentLocatedStay
                                            .getLocation().getBlock().getId() : -1) + ") via modes " +
                                    currentLocatedStay.getModeArr().getName() + "/" +
                                    currentLocatedStay.getModeDep().getName());
        }

        TPS_PlannedTrip planned_trip = plan.getPlannedTrip(associated_trip);
        planned_trip.setTravelTime(plan.getLocatedStay(departure_stay), plan.getLocatedStay(arrival_stay));

        //now update travel times and init guards
        TPS_LocatedStay departure_located_stay = plan.getLocatedStay(departure_stay);
        TPS_LocatedStay arrival_located_stay = plan.getLocatedStay(arrival_stay);

        planned_trip.setStart(departure_located_stay.getStart()+departure_located_stay.getDuration());
        arrival_located_stay.setStart(departure_located_stay.getStart()+departure_located_stay.getDuration()+planned_trip.getDuration());


        //update the travel durations for this plan
        tour_part.updateActualTravelDurations(plan);

        //now we set a guard to the calling_state in order to transition into the ON_MOVE state when the trip starts
        //the ON_MOVE state will have a handler added that will transition into the ON_ACTIVITY state when the activity starts
        //the ON_ACTIVITY state will have a handler to trigger the next trip state

        trip_state.addHandler(TPS_PlanEventType.SIMULATION_STEP, post_trip_activity_state, StateMachineUtils.NoAction(), i -> (int ) i == (int) (arrival_located_stay.getStart() * 1.66666666e-2 + 0.5));

        post_trip_activity_state.addHandler(TPS_PlanEventType.SIMULATION_STEP, post_activity_trip_state, StateMachineUtils.NoAction(), i -> (int) i == (int) ((arrival_located_stay.getStart() + arrival_located_stay.getDuration()) * 1.66666666e-2 + 0.5));

        if (TPS_Logger.isLogging(TPS_LoggingInterface.SeverenceLogLevel.DEBUG)) {
            TPS_Logger.log(TPS_LoggingInterface.SeverenceLogLevel.DEBUG, "on move state handler at time: " + (int) (planned_trip.getStart() * 1.66666666e-2 + 0.5));
            TPS_Logger.log(TPS_LoggingInterface.SeverenceLogLevel.DEBUG, "on activity state handler at time: "+(int) (plan.getLocatedStay(arrival_stay).getStart() * 1.66666666e-2 + 0.5));
                    TPS_Logger.log(TPS_LoggingInterface.SeverenceLogLevel.DEBUG,"activity end handler at time: "+(int) ((arrival_located_stay.getStart() +arrival_located_stay.getDuration())* 1.66666666e-2 + 0.5));
        }

        plan.getAttributes().put(TPS_AttributeReader.TPS_Attribute.CURRENT_TAZ_SETTLEMENT_CODE_TAPAS,
                currentLocatedStay.getLocation().getTrafficAnalysisZone().getBbrType()
                        .getCode(TPS_SettlementSystem.TPS_SettlementSystemType.TAPAS));

    }
}
