package ez.backend.turbo.utils;

import com.fasterxml.jackson.annotation.JsonValue;

public enum MessageType {

    PA_REQUEST_ACCEPTED("pa_request_accepted"),
    PA_SIMULATION_START("pa_simulation_start"),
    HEARTBEAT("heartbeat"),
    SUCCESS_PROCESS("success_process"),
    ERROR_GLOBAL("error_global"),

    DATA_TEXT_OVERVIEW("data_text_overview"),

    DATA_TEXT_PARAGRAPH1_EMISSIONS("data_text_paragraph1_emissions"),
    DATA_TEXT_PARAGRAPH2_EMISSIONS("data_text_paragraph2_emissions"),
    DATA_CHART_BAR_EMISSIONS("data_chart_bar_emissions"),
    DATA_CHART_PIE_EMISSIONS("data_chart_pie_emissions"),
    SUCCESS_MAP_EMISSIONS("success_map_emissions"),
    ERROR_MAP_EMISSIONS("error_map_emissions"),

    DATA_TEXT_PARAGRAPH1_PEOPLE_RESPONSE("data_text_paragraph1_people_response"),
    DATA_TEXT_PARAGRAPH2_PEOPLE_RESPONSE("data_text_paragraph2_people_response"),
    DATA_CHART_BREAKDOWN_PEOPLE_RESPONSE("data_chart_breakdown_people_response"),
    DATA_CHART_TIME_IMPACT_PEOPLE_RESPONSE("data_chart_time_impact_people_response"),
    SUCCESS_MAP_PEOPLE_RESPONSE("success_map_people_response"),
    ERROR_MAP_PEOPLE_RESPONSE("error_map_people_response"),

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
