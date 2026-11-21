package com.example.retailx.core.common

data class IndianState(
    val code: String,
    val name: String
) {
    val label: String get() = "$code - $name"
}

object IndianStates {
    val all: List<IndianState> = listOf(
        IndianState("01", "Jammu & Kashmir"),
        IndianState("02", "Himachal Pradesh"),
        IndianState("03", "Punjab"),
        IndianState("04", "Chandigarh"),
        IndianState("05", "Uttarakhand"),
        IndianState("06", "Haryana"),
        IndianState("07", "Delhi"),
        IndianState("08", "Rajasthan"),
        IndianState("09", "Uttar Pradesh"),
        IndianState("10", "Bihar"),
        IndianState("11", "Sikkim"),
        IndianState("12", "Arunachal Pradesh"),
        IndianState("13", "Nagaland"),
        IndianState("14", "Manipur"),
        IndianState("15", "Mizoram"),
        IndianState("16", "Tripura"),
        IndianState("17", "Meghalaya"),
        IndianState("18", "Assam"),
        IndianState("19", "West Bengal"),
        IndianState("20", "Jharkhand"),
        IndianState("21", "Odisha"),
        IndianState("22", "Chhattisgarh"),
        IndianState("23", "Madhya Pradesh"),
        IndianState("24", "Gujarat"),
        IndianState("26", "Dadra & Nagar Haveli and Daman & Diu"),
        IndianState("27", "Maharashtra"),
        IndianState("29", "Karnataka"),
        IndianState("30", "Goa"),
        IndianState("31", "Lakshadweep"),
        IndianState("32", "Kerala"),
        IndianState("33", "Tamil Nadu"),
        IndianState("34", "Puducherry"),
        IndianState("35", "Andaman & Nicobar Islands"),
        IndianState("36", "Telangana"),
        IndianState("37", "Andhra Pradesh"),
        IndianState("38", "Ladakh")
    )

    fun findByCode(code: String): IndianState? = all.firstOrNull { it.code == code }

    private val gstinRegex = Regex("^[0-9]{2}[A-Z]{5}[0-9]{4}[A-Z]{1}[1-9A-Z]{1}Z[0-9A-Z]{1}$")

    fun isValidGstin(gstin: String): Boolean {
        if (gstin.isBlank()) return true
        val upper = gstin.trim().uppercase()
        return gstinRegex.matches(upper) && findByCode(upper.take(2)) != null
    }

    fun extractStateCodeFromGstin(gstin: String): String? {
        val upper = gstin.trim().uppercase()
        return if (upper.length >= 2) findByCode(upper.take(2))?.code else null
    }
}
