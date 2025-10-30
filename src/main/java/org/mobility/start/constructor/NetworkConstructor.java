package org.mobility.start.constructor;

import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.network.Link;
import org.matsim.api.core.v01.network.Network;
import org.matsim.core.network.NetworkUtils;
import org.matsim.core.network.io.MatsimNetworkReader;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.Files;
import java.util.List;
import java.util.Map;

@Component
public class NetworkConstructor {
    @Value("${matsim.input-directory}")
    private String inputDirectory;

    public Network constructNetwork(Map<String, Object> request) {
        Path basePath = Paths.get(System.getProperty("user.dir"));
        Path networkFilePath = basePath.resolve(inputDirectory).resolve("network.xml").normalize();
        
        if (!Files.exists(networkFilePath)) {
            throw new RuntimeException("Network file not found: " + networkFilePath);
        }

        Network network = NetworkUtils.createNetwork();
        try {
            new MatsimNetworkReader(network).readFile(networkFilePath.toString());
        } catch (Exception e) {
            throw new RuntimeException("Failed to read network file: " + e.getMessage());
        }

        if (network.getNodes().isEmpty() || network.getLinks().isEmpty()) {
            throw new RuntimeException("Network file contains no nodes or links");
        }

        if (request.containsKey("zoneLinks")) {
            List<?> zoneLinks = (List<?>) request.get("zoneLinks");
            for (Object linkObj : zoneLinks) {
                String linkId = linkObj.toString().trim();
                Id<Link> matsimLinkId = Id.create(linkId, Link.class);
                if (!network.getLinks().containsKey(matsimLinkId)) {
                    throw new RuntimeException("Zone link not found in network: " + linkId);
                }
            }
        }
        
        return network;
    }
}
