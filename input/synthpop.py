from datetime import datetime, timedelta
import xml.etree.ElementTree as ET
from xml.dom import minidom
import os
import sys
import random

def create_population(num_agents, trips_per_agent, start_hour, end_hour):
    print("\nInitializing population generation...")
    
    # Convert hours to datetime
    start_time = datetime.now().replace(hour=start_hour, minute=0, second=0, microsecond=0)
    end_time = datetime.now().replace(hour=end_hour, minute=0, second=0, microsecond=0)
    if end_hour <= start_hour:
        end_time += timedelta(days=1)
    
    # Calculate time distribution
    time_window = (end_time - start_time).total_seconds()
    total_trips = num_agents * trips_per_agent
    time_per_trip = time_window / total_trips
    
    print(f"Time window: {start_hour:02d}:00:00 - {end_hour:02d}:00:00")
    print(f"Total trips: {total_trips}")
    print(f"Average time between trips: {time_per_trip/60:.1f} minutes")
    
    # Vehicle types
    vehicle_types = ['ev', 'lev', 'hev']
    
    # Create population
    population = ET.Element("population")
    
    # Create agents
    for agent_num in range(num_agents):
        # Rotate vehicle types evenly
        vehicle_type = vehicle_types[agent_num % len(vehicle_types)]
        agent_id = f"{vehicle_type}_agent_{agent_num}"
        
        # Create person
        person = ET.SubElement(population, "person")
        person.set("id", agent_id)
        
        # Set vehicle type attribute
        attributes = ET.SubElement(person, "attributes")
        vehicle_attr = ET.SubElement(attributes, "attribute")
        vehicle_attr.set("name", "vehicleType")
        vehicle_attr.set("class", "java.lang.String")
        vehicle_attr.text = f"{vehicle_type}_car"
        
        # Create plan
        plan = ET.SubElement(person, "plan")
        plan.set("selected", "yes")
        
        # Generate trips
        for trip_num in range(trips_per_agent):
            # Calculate trip time with even distribution
            trip_slot = (agent_num * trips_per_agent + trip_num)
            trip_time = start_time + timedelta(seconds=trip_slot * time_per_trip)
            
            # Add small random variation (±10% of time_per_trip)
            max_variation = int(time_per_trip * 0.1)
            variation = random.randint(-max_variation, max_variation)
            trip_time += timedelta(seconds=variation)
            
            # Alternate between west-to-east and east-to-west
            is_westbound = (trip_num % 2 == 1)
            
            # First activity
            first_act = ET.SubElement(plan, "activity")
            first_act.set("type", "work" if is_westbound else "home")
            first_act.set("link", "6" if is_westbound else "1") # Using links 1 and 6 for start/end
            first_act.set("end_time", trip_time.strftime("%H:%M:%S"))
            
            # Leg (trip)
            leg = ET.SubElement(plan, "leg")
            leg.set("mode", "car")
            
            # Route
            route = ET.SubElement(leg, "route")
            route.set("type", "links")
            
            # Define routes
            if is_westbound:
                route.text = "6,4,2"  # East to West route
            else:
                route.text = "1,3,5"  # West to East route
            
            # Final activity
            final_act = ET.SubElement(plan, "activity")
            final_act.set("type", "home" if is_westbound else "work")
            final_act.set("link", "1" if is_westbound else "6")
            
            # Add end time if not the last activity
            if trip_num < trips_per_agent - 1:
                next_trip_time = trip_time + timedelta(minutes=30)
                final_act.set("end_time", next_trip_time.strftime("%H:%M:%S"))
        
        print(f"Created agent {agent_id} with {trips_per_agent} trips")

    return format_xml(population)

def format_xml(population):
    xml_str = minidom.parseString(ET.tostring(population)).toprettyxml(indent="    ")
    doctype = '<!DOCTYPE population SYSTEM "http://www.matsim.org/files/dtd/population_v6.dtd">'
    xml_str = xml_str.replace('<?xml version="1.0" ?>', 
                             f'<?xml version="1.0" encoding="utf-8"?>\n{doctype}')
    return xml_str

def main():
    print("\nMATSim Population Generator")
    print("==========================")
    
    try:
        # Get user input
        num_agents = int(input("\nEnter number of agents: "))
        if num_agents <= 0:
            raise ValueError("Number of agents must be positive")
            
        trips_per_agent = int(input("Enter number of trips per agent: "))
        if trips_per_agent <= 0:
            raise ValueError("Number of trips must be positive")
            
        start_hour = int(input("Enter start hour (0-23): "))
        if not 0 <= start_hour <= 23:
            raise ValueError("Start hour must be between 0 and 23")
            
        end_hour = int(input("Enter end hour (0-23): "))
        if not 0 <= end_hour <= 23:
            raise ValueError("End hour must be between 0 and 23")
        
        # Generate population
        population_content = create_population(
            num_agents,
            trips_per_agent,
            start_hour,
            end_hour
        )
        
        # Write to file
        output_file = 'population.xml'
        if os.path.exists(output_file):
            response = input(f"\n{output_file} already exists. Overwrite? (y/n): ").lower()
            if response != 'y':
                timestamp = datetime.now().strftime("%Y%m%d_%H%M%S")
                output_file = f'population_{timestamp}.xml'
        
        with open(output_file, 'w', encoding='utf-8') as f:
            f.write(population_content)
        print(f"\nPopulation file generated: {output_file}")
        
    except ValueError as e:
        print(f"\nError: {str(e)}")
        sys.exit(1)
    except Exception as e:
        print(f"\nUnexpected error: {str(e)}")
        sys.exit(1)

if __name__ == "__main__":
    main()