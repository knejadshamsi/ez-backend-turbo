# MATSim Default Files

This directory contains default/baseline MATSim configuration files that users can reference when submitting simulation requests.

## Files

### `config.xml`
Default immutable MATSim simulation configuration.

### `vehicle_types.xml`
Default vehicle type definitions.

### `vehicleDefinitions_v1.0.xsd`
XML Schema Definition for validating vehicle definition files. Used for validation of user-provided vehicle XML files.

## Usage
When startng the server, the user must provide a yaml file that refrences the requied files.
