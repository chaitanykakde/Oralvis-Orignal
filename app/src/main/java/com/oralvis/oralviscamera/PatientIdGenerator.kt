package com.oralvis.oralviscamera

object PatientIdGenerator {
    
    /**
     * Generates Global Patient ID using the algorithm: {LLLLL}{AA}{NNNNN}
     * 
     * @param fullName Full name of the patient (will extract last name)
     * @param age Age of the patient (2 digits)
     * @param phoneNumber Phone number (will extract last 5 digits)
     * @return Generated patient ID (e.g., "ANDER3534567")
     */
    fun generateGlobalPatientId(fullName: String, age: Int, phoneNumber: String): String {
        // Extract last name (assume last word is last name)
        val nameParts = fullName.trim().split("\\s+".toRegex())
        val lastName = if (nameParts.isNotEmpty()) nameParts.last() else fullName
        
        // LLLLL: First 5 letters of Last Name (uppercase, strip non-alphabets, pad if needed)
        val lastNameLetters = lastName.filter { it.isLetter() }.uppercase()
        val lllll = if (lastNameLetters.length >= 5) {
            lastNameLetters.substring(0, 5)
        } else {
            lastNameLetters.padEnd(5, 'X') // Pad with 'X' if less than 5 characters
        }
        
        // AA: 2 digits representing Age (zero-padded)
        val aa = String.format("%02d", age)
        
        // NNNNN: Last 5 digits of Phone Number
        val phoneDigits = phoneNumber.filter { it.isDigit() }
        val nnnnn = if (phoneDigits.length >= 5) {
            phoneDigits.takeLast(5)
        } else {
            phoneDigits.padStart(5, '0') // Pad with '0' if less than 5 digits
        }
        
        return "$lllll$aa$nnnnn"
    }
}

