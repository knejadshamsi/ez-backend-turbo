package org.ez.mobility.ez;

import org.matsim.core.config.ReflectiveConfigGroup;
import java.util.Map;
import java.util.HashMap;
import java.util.List;
import java.time.LocalTime;
import java.time.format.DateTimeParseException;
import java.io.File;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import javax.xml.transform.OutputKeys;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class TransitConfigGroup extends ReflectiveConfigGroup {
    private static final Logger logger = LoggerFactory.getLogger(TransitConfigGroup.class);
    public static final String GROUP_NAME = "transitConfig";

    private final Map<String, TransitStop> transitStops;
    private final Map<String, TransitLine> transitLines;

    public TransitConfigGroup() {
        super(GROUP_NAME);
        this.transitStops = new HashMap<>();
        this.transitLines = new HashMap<>();
    }

    public static class TransitStop {
        private final String id;
        private final double x;
        private final double y;
        private final String mode; // "metro" or "bus"

        public TransitStop(String id, double x, double y, String mode) {
            validateMode(mode);
            this.id = id;
            this.x = x;
            this.y = y;
            this.mode = mode;
        }

        private static void validateMode(String mode) {
            if (!"metro".equals(mode) && !"bus".equals(mode)) {
                throw new TransitConfigurationException("Invalid transit mode: " + mode + ". Must be 'metro' or 'bus'");
            }
        }

        public String getId() { return id; }
        public double getX() { return x; }
        public double getY() { return y; }
        public String getMode() { return mode; }
    }

    public static class TransitLine {
        private final String id;
        private final String mode;
        private final LocalTime startTime;
        private final LocalTime endTime;
        private final double interval; // in minutes
        private final double speed;    // in km/h
        private final String[] stopSequence;

        public TransitLine(String id, String mode, LocalTime startTime, LocalTime endTime, 
                          double interval, double speed, String[] stopSequence) {
            validateTransitLine(id, mode, interval, speed, stopSequence);
            this.id = id;
            this.mode = mode;
            this.startTime = startTime;
            this.endTime = endTime;
            this.interval = interval;
            this.speed = speed;
            this.stopSequence = stopSequence.clone();
        }

        private static void validateTransitLine(String id, String mode, double interval, 
                                              double speed, String[] stopSequence) {
            if (id == null || id.trim().isEmpty()) {
                throw new TransitConfigurationException("Transit line ID cannot be empty");
            }
            if (!"metro".equals(mode) && !"bus".equals(mode)) {
                throw new TransitConfigurationException("Invalid transit mode: " + mode);
            }
            if (interval <= 0) {
                throw new TransitConfigurationException("Invalid interval: " + interval);
            }
            if (speed <= 0) {
                throw new TransitConfigurationException("Invalid speed: " + speed);
            }
            if (stopSequence == null || stopSequence.length < 2) {
                throw new TransitConfigurationException("Transit line must have at least 2 stops");
            }
        }

        public String getId() { return id; }
        public String getMode() { return mode; }
        public LocalTime getStartTime() { return startTime; }
        public LocalTime getEndTime() { return endTime; }
        public double getInterval() { return interval; }
        public double getSpeed() { return speed; }
        public String[] getStopSequence() { return stopSequence.clone(); }
    }

    public void generateTransitFiles(String baseDir) {
        try {
            validateTransitConfig();
            File ptDir = new File(baseDir, "pt");
            if (!ptDir.exists() && !ptDir.mkdirs()) {
                throw new TransitConfigurationException("Failed to create PT directory: " + ptDir);
            }
            generateTransitSchedule(new File(ptDir, "transitSchedule.xml").getPath());
            generateTransitVehicles(new File(ptDir, "transitVehicles.xml").getPath());
        } catch (Exception e) {
            throw new TransitConfigurationException("Failed to generate transit files: " + e.getMessage(), e);
        }
    }

    private void validateTransitConfig() {
        if (transitStops.isEmpty()) {
            throw new TransitConfigurationException("No transit stops defined");
        }
        if (transitLines.isEmpty()) {
            throw new TransitConfigurationException("No transit lines defined");
        }

        // Validate that all stops referenced in lines exist
        for (TransitLine line : transitLines.values()) {
            for (String stopId : line.getStopSequence()) {
                if (!transitStops.containsKey(stopId)) {
                    throw new TransitConfigurationException(
                        "Transit line " + line.getId() + " references non-existent stop: " + stopId);
                }
            }
        }
    }

    private void generateTransitSchedule(String filePath) throws Exception {
        DocumentBuilderFactory docFactory = DocumentBuilderFactory.newInstance();
        docFactory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        DocumentBuilder docBuilder = docFactory.newDocumentBuilder();
        Document doc = docBuilder.newDocument();

        Element transitSchedule = doc.createElement("transitSchedule");
        doc.appendChild(transitSchedule);

        generateTransitStops(doc, transitSchedule);
        generateTransitLines(doc, transitSchedule);

        writeXmlFile(doc, filePath);
    }

    private void generateTransitStops(Document doc, Element transitSchedule) {
        Element transitStopsElem = doc.createElement("transitStops");
        transitSchedule.appendChild(transitStopsElem);

        for (TransitStop stop : transitStops.values()) {
            Element stopFacility = doc.createElement("stopFacility");
            stopFacility.setAttribute("id", stop.getId());
            stopFacility.setAttribute("x", String.valueOf(stop.getX()));
            stopFacility.setAttribute("y", String.valueOf(stop.getY()));
            transitStopsElem.appendChild(stopFacility);
        }
    }

    private void generateTransitLines(Document doc, Element transitSchedule) {
        for (TransitLine line : transitLines.values()) {
            Element transitLine = doc.createElement("transitLine");
            transitLine.setAttribute("id", line.getId());
            
            Element transitRoute = doc.createElement("transitRoute");
            transitRoute.setAttribute("id", line.getId() + "_route");

            Element transportMode = doc.createElement("transportMode");
            transportMode.setTextContent(line.getMode());
            transitRoute.appendChild(transportMode);

            generateRouteProfile(doc, transitRoute, line);
            generateDepartures(doc, transitRoute, line);
            
            transitLine.appendChild(transitRoute);
            transitSchedule.appendChild(transitLine);
        }
    }

    private void generateRouteProfile(Document doc, Element transitRoute, TransitLine line) {
        Element routeProfile = doc.createElement("routeProfile");
        for (String stopId : line.getStopSequence()) {
            Element stop = doc.createElement("stop");
            stop.setAttribute("refId", stopId);
            routeProfile.appendChild(stop);
        }
        transitRoute.appendChild(routeProfile);
    }

    private void generateDepartures(Document doc, Element transitRoute, TransitLine line) {
        Element departures = doc.createElement("departures");
        LocalTime currentTime = line.getStartTime();
        int departureId = 1;
        
        while (!currentTime.isAfter(line.getEndTime())) {
            Element departure = doc.createElement("departure");
            departure.setAttribute("id", String.valueOf(departureId++));
            departure.setAttribute("departureTime", currentTime.toString());
            departure.setAttribute("vehicleRefId", line.getId() + "_vehicle");
            departures.appendChild(departure);
            currentTime = currentTime.plusMinutes((long) line.getInterval());
        }
        transitRoute.appendChild(departures);
    }

    private void generateTransitVehicles(String filePath) throws Exception {
        DocumentBuilderFactory docFactory = DocumentBuilderFactory.newInstance();
        docFactory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        DocumentBuilder docBuilder = docFactory.newDocumentBuilder();
        Document doc = docBuilder.newDocument();

        Element vehicleDefinitions = doc.createElement("vehicleDefinitions");
        doc.appendChild(vehicleDefinitions);

        addVehicleType(doc, vehicleDefinitions, "metro_type", 200, 300, 100.0, 3.0);
        addVehicleType(doc, vehicleDefinitions, "bus_type", 40, 60, 12.0, 2.5);

        for (TransitLine line : transitLines.values()) {
            Element vehicle = doc.createElement("vehicle");
            vehicle.setAttribute("id", line.getId() + "_vehicle");
            vehicle.setAttribute("type", line.getMode().equals("metro") ? "metro_type" : "bus_type");
            vehicleDefinitions.appendChild(vehicle);
        }

        writeXmlFile(doc, filePath);
    }

    private void writeXmlFile(Document doc, String filePath) throws Exception {
        TransformerFactory transformerFactory = TransformerFactory.newInstance();
        Transformer transformer = transformerFactory.newTransformer();
        transformer.setOutputProperty(OutputKeys.INDENT, "yes");
        transformer.setOutputProperty("{http://xml.apache.org/xslt}indent-amount", "2");
        
        DOMSource source = new DOMSource(doc);
        StreamResult result = new StreamResult(new File(filePath));
        transformer.transform(source, result);
    }

    private void addVehicleType(Document doc, Element parent, String id, int seats, int standing, 
                               double length, double width) {
        Element vehicleType = doc.createElement("vehicleType");
        vehicleType.setAttribute("id", id);

        Element capacity = doc.createElement("capacity");
        Element seatsElem = doc.createElement("seats");
        seatsElem.setAttribute("persons", String.valueOf(seats));
        Element standingElem = doc.createElement("standingRoom");
        standingElem.setAttribute("persons", String.valueOf(standing));
        capacity.appendChild(seatsElem);
        capacity.appendChild(standingElem);

        Element lengthElem = doc.createElement("length");
        lengthElem.setAttribute("meter", String.valueOf(length));

        Element widthElem = doc.createElement("width");
        widthElem.setAttribute("meter", String.valueOf(width));

        vehicleType.appendChild(capacity);
        vehicleType.appendChild(lengthElem);
        vehicleType.appendChild(widthElem);

        parent.appendChild(vehicleType);
    }

    public void addTransitStop(TransitStop stop) {
        this.transitStops.put(stop.getId(), stop);
    }

    public void addTransitLine(TransitLine line) {
        this.transitLines.put(line.getId(), line);
    }

    public Map<String, TransitStop> getTransitStops() {
        return new HashMap<>(transitStops);
    }

    public Map<String, TransitLine> getTransitLines() {
        return new HashMap<>(transitLines);
    }

    public void removeTransitStop(String stopId) {
        transitStops.remove(stopId);
    }

    public void removeTransitLine(String lineId) {
        transitLines.remove(lineId);
    }

    public void clearTransitStops() {
        transitStops.clear();
    }

    public void clearTransitLines() {
        transitLines.clear();
    }

    public void updateFromPolicy(List<org.ez.mobility.api.SimulationRequest.PolicyEntry> policies) {
        clearTransitStops();
        clearTransitLines();

        try {
            for (org.ez.mobility.api.SimulationRequest.PolicyEntry policy : policies) {
                if (policy.getMode().equals("transit_stop")) {
                    processTransitStop(policy);
                } else if (policy.getMode().equals("transit_line")) {
                    processTransitLine(policy);
                }
            }
        } catch (Exception e) {
            throw new TransitConfigurationException("Failed to update from policy: " + e.getMessage(), e);
        }
    }

    private void processTransitStop(org.ez.mobility.api.SimulationRequest.PolicyEntry policy) {
        try {
            String[] coords = policy.getId().split(",");
            if (coords.length != 2) {
                throw new TransitConfigurationException("Invalid transit stop coordinates: " + policy.getId());
            }
            
            addTransitStop(new TransitStop(
                "stop_" + policy.getId(),
                Double.parseDouble(coords[0]),
                Double.parseDouble(coords[1]),
                policy.getOptions().getMode()
            ));
        } catch (NumberFormatException e) {
            throw new TransitConfigurationException("Invalid coordinate format: " + policy.getId());
        }
    }

    private void processTransitLine(org.ez.mobility.api.SimulationRequest.PolicyEntry policy) {
        try {
            String[] modeAndSpeed = policy.getOptions().getMode().split(",");
            if (modeAndSpeed.length != 2) {
                throw new TransitConfigurationException("Invalid transit line mode format: " + policy.getOptions().getMode());
            }

            addTransitLine(new TransitLine(
                policy.getId(),
                modeAndSpeed[0],
                parseTime(policy.getOptions().getPrice()),
                parseTime(policy.getOptions().getPenalty()),
                policy.getOptions().getInterval(),
                Double.parseDouble(modeAndSpeed[1]),
                policy.getId().split(";")
            ));
        } catch (DateTimeParseException e) {
            throw new TransitConfigurationException("Invalid time format in transit line: " + e.getMessage());
        } catch (NumberFormatException e) {
            throw new TransitConfigurationException("Invalid number format in transit line: " + e.getMessage());
        }
    }

    private LocalTime parseTime(Double timeValue) {
        if (timeValue == null) {
            throw new TransitConfigurationException("Time value cannot be null");
        }
        int hours = timeValue.intValue();
        int minutes = (int) ((timeValue - hours) * 60);
        return LocalTime.of(hours, minutes);
    }

    public static class TransitConfigurationException extends RuntimeException {
        public TransitConfigurationException(String message) {
            super(message);
        }

        public TransitConfigurationException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
