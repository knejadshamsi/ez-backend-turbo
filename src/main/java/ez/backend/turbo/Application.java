package ez.backend.turbo;

import ez.backend.turbo.config.StartupValidator;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

import java.util.List;

@SpringBootApplication
@EnableScheduling
public class Application {

    public static void main(String[] args) {
        if (args.length == 0) {
            StartupValidator.printErrors(List.of(
                    "No arguments provided | Aucun argument fourni",
                    "Usage: java -jar ez-backend-turbo.jar <config.yml>",
                    "       java -jar ez-backend-turbo.jar --dev"
            ));
            return;
        }

        boolean hasDev = false;
        String configPath = null;
        int fileCount = 0;
        for (String arg : args) {
            if ("--dev".equals(arg)) {
                hasDev = true;
            } else {
                configPath = arg;
                fileCount++;
            }
        }

        if (fileCount > 1) {
            StartupValidator.printErrors(List.of(
                    "Only one configuration file is allowed"
                            + " | Un seul fichier de configuration est permis",
                    "Usage: java -jar ez-backend-turbo.jar <config.yml>",
                    "       java -jar ez-backend-turbo.jar --dev"
            ));
            return;
        }

        if (hasDev && configPath != null) {
            StartupValidator.printErrors(List.of(
                    "Cannot use --dev with a configuration file"
                            + " | Impossible d'utiliser --dev avec un fichier de configuration",
                    "Provide a config file OR use --dev, not both"
                            + " | Fournissez un fichier OU utilisez --dev, pas les deux"
            ));
            return;
        }

        if (hasDev) {
            SpringApplication.run(Application.class, new String[]{"--ez.data.root=./dev-data"});
        } else {
            if (!StartupValidator.checkConfigFile(configPath)) {
                return;
            }
            SpringApplication.run(Application.class,
                    new String[]{"--spring.config.additional-location=file:" + configPath});
        }
    }
}
