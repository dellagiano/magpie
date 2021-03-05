package io.openraven.nightglow.cli;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import io.openraven.nightglow.api.Session;
import io.openraven.nightglow.core.Orchestrator;
import io.openraven.nightglow.core.config.NightglowConfig;
import org.apache.commons.cli.DefaultParser;
import org.apache.commons.cli.HelpFormatter;
import org.apache.commons.cli.Option;
import org.apache.commons.cli.Options;
import org.apache.commons.cli.ParseException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.FileInputStream;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;

public class Main {

  private static final Logger LOGGER = LoggerFactory.getLogger(Main.class);
  private static final ObjectMapper MAPPER = new ObjectMapper(new YAMLFactory());
  private static final String DEFAULT_CONFIG_FILE = "config.yaml";

  public static String humanReadableFormat(Duration duration) {
    return duration.toString()
      .substring(2)
      .replaceAll("(\\d[HMS])(?!$)", "$1 ")
      .toLowerCase();
  }

  public static void main(String[] args) throws IOException, ParseException {
    final var start = Instant.now();

    final var options = new Options();
    options.addOption(new Option("f", "configfile", true, "Config file location (defaults to " + DEFAULT_CONFIG_FILE + ")"));

    final var parser = new DefaultParser();
    final var cmd = parser.parse( options, args);

    var configFile = cmd.getOptionValue("f");
    if (configFile == null) {
      configFile = DEFAULT_CONFIG_FILE;
    }

    try(var is = new FileInputStream((configFile))) {
      final var config = MAPPER.readValue(is, NightglowConfig.class);
      LOGGER.info("OSS Discovery. Classpath={}", System.getProperties().get("java.class.path"));
      new Orchestrator(config, new Session()).scan();
    }
    LOGGER.info("Discovery completed in {}", humanReadableFormat(Duration.between(start, Instant.now())));
  }
}
