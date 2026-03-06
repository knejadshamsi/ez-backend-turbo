package ez.backend.turbo.utils;

import com.fasterxml.jackson.annotation.JsonValue;

public enum MessageType {

    PA_REQUEST_ACCEPTED("pa_request_accepted"),
    PA_SIMULATION_START("pa_simulation_start"),
    HEARTBEAT("heartbeat"),
    PA_CANCELLED_PROCESS("pa_cancelled_process"),
    SUCCESS_PROCESS("success_process"),
    ERROR_GLOBAL("error_global"),
    ERROR_VALIDATION("error_validation"),

    PA_PREPROCESSING_POPULATION_STARTED("pa_preprocessing_population_started"),
    PA_PREPROCESSING_POPULATION_COMPLETE("pa_preprocessing_population_complete"),
    PA_PREPROCESSING_NETWORK_STARTED("pa_preprocessing_network_started"),
    PA_PREPROCESSING_NETWORK_COMPLETE("pa_preprocessing_network_complete"),
    PA_PREPROCESSING_TRANSIT_STARTED("pa_preprocessing_transit_started"),
    PA_PREPROCESSING_TRANSIT_COMPLETE("pa_preprocessing_transit_complete"),
    PA_PREPROCESSING_CONFIG_STARTED("pa_preprocessing_config_started"),
    PA_PREPROCESSING_CONFIG_COMPLETE("pa_preprocessing_config_complete"),
    PA_SIMULATION_BASE_STARTED("pa_simulation_base_started"),
    PA_SIMULATION_BASE_COMPLETE("pa_simulation_base_complete"),
    PA_SIMULATION_POLICY_STARTED("pa_simulation_policy_started"),
    PA_SIMULATION_POLICY_COMPLETE("pa_simulation_policy_complete"),
    PA_POSTPROCESSING_OVERVIEW_STARTED("pa_postprocessing_overview_started"),
    PA_POSTPROCESSING_OVERVIEW_COMPLETE("pa_postprocessing_overview_complete"),
    PA_POSTPROCESSING_EMISSIONS_STARTED("pa_postprocessing_emissions_started"),
    PA_POSTPROCESSING_EMISSIONS_COMPLETE("pa_postprocessing_emissions_complete"),
    PA_POSTPROCESSING_PEOPLE_RESPONSE_STARTED("pa_postprocessing_people_response_started"),
    PA_POSTPROCESSING_PEOPLE_RESPONSE_COMPLETE("pa_postprocessing_people_response_complete"),
    PA_POSTPROCESSING_TRIP_LEGS_STARTED("pa_postprocessing_trip_legs_started"),
    PA_POSTPROCESSING_TRIP_LEGS_COMPLETE("pa_postprocessing_trip_legs_complete"),

    SCENARIO_STATUS("scenario_status"),
    SCENARIO_INPUT("scenario_input"),
    SCENARIO_SESSION("scenario_session"),

    DATA_TEXT_OVERVIEW("data_text_overview"),

    DATA_TEXT_PARAGRAPH1_EMISSIONS("data_text_paragraph1_emissions"),
    DATA_TEXT_PARAGRAPH2_EMISSIONS("data_text_paragraph2_emissions"),
    DATA_CHART_BAR_EMISSIONS("data_chart_bar_emissions"),
    DATA_CHART_PIE_EMISSIONS("data_chart_pie_emissions"),
    DATA_CHART_LINE_EMISSIONS("data_chart_line_emissions"),
    DATA_CHART_STACKED_BAR_EMISSIONS("data_chart_stacked_bar_emissions"),
    DATA_WARM_COLD_INTENSITY_EMISSIONS("data_warm_cold_intensity_emissions"),
    DATA_GLOBAL_CONTEXT("data_global_context"),
    SUCCESS_MAP_EMISSIONS("success_map_emissions"),
    ERROR_MAP_EMISSIONS("error_map_emissions"),

    DATA_TEXT_PARAGRAPH1_PEOPLE_RESPONSE("data_text_paragraph1_people_response"),
    DATA_CHART_SANKEY_PEOPLE_RESPONSE("data_chart_sankey_people_response"),
    DATA_CHART_BAR_PEOPLE_RESPONSE("data_chart_bar_people_response"),
    SUCCESS_MAP_PEOPLE_RESPONSE("success_map_people_response"),
    ERROR_MAP_PEOPLE_RESPONSE("error_map_people_response"),

    DATA_TEXT_PARAGRAPH1_TRIP_LEGS("data_text_paragraph1_trip_legs"),
    DATA_TABLE_TRIP_LEGS("data_table_trip_legs"),
    SUCCESS_MAP_TRIP_LEGS("success_map_trip_legs"),
    ERROR_MAP_TRIP_LEGS("error_map_trip_legs");

    private final String value;

    MessageType(String value) {
        this.value = value;
    }

    @JsonValue
    public String getValue() {
        return value;
    }
}
