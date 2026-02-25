package com.traintracker

enum class BoardType(val label: String, val soapAction: String) {
    DEPARTURES("Departures", "GetDepartureBoard"),
    ARRIVALS("Arrivals", "GetArrivalBoard"),
    ALL("All Services", "GetArrivalDepartureBoard")
}
