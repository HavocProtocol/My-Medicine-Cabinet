package com.example.data

import kotlinx.coroutines.delay

data class SfdaDrugInfo(
    val tradeName: String,
    val manufacturer: String,
    val barcode: String
)

object SfdaRepository {
    suspend fun lookupGtin(gtin: String): SfdaDrugInfo {
        // Simulate network delay for SFDA / GS1 Saudi Arabia API
        delay(2000)
        
        // Mocked SFDA / GS1 Saudi Arabia database for demonstration purposes
        return when (gtin) {
            "6281086002035" -> SfdaDrugInfo("Panadol Advance 500mg", "GlaxoSmithKline (GSK)", gtin)
            "6281074000350" -> SfdaDrugInfo("Fevadol 500mg", "SPIMACO", gtin)
            "6285095000002" -> SfdaDrugInfo("Rovamac 3M I.U.", "Tabuk Pharmaceuticals", gtin)
            "6281086000109" -> SfdaDrugInfo("Amoxil 500mg", "GlaxoSmithKline (GSK)", gtin)
            "6281074002040" -> SfdaDrugInfo("Profenal 400mg", "SPIMACO", gtin)
            "6281074001992" -> SfdaDrugInfo("Klavox 1g", "SPIMACO", gtin)
            "6280000000000" -> SfdaDrugInfo("Sample Saudi Drug", "Sample Manufacturer SA", gtin)
            else -> {
                // If not in the local mock, return a fallback showing it was resolved via GTIN fallback
                SfdaDrugInfo("Unknown Drug (GTIN: $gtin)", "Unknown Manufacturer", gtin)
            }
        }
    }
}
