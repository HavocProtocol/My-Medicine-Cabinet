package com.example.ui.utils

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.example.R
import com.example.data.Category

@Composable
fun Category.getLocalizedName(): String {
    if (!this.isDefault) return this.name
    return when (this.name) {
        "صدرية وتنفسية", "Respiratory" -> stringResource(R.string.cat_respiratory)
        "بطنية وهضمية", "Gastrointestinal" -> stringResource(R.string.cat_gastro)
        "أنف وأذن وحنجرة", "ENT" -> stringResource(R.string.cat_ent)
        "مسكنات وخافضات حرارة", "Painkillers & Antipyretics" -> stringResource(R.string.cat_painkillers)
        "حساسية", "Allergy" -> stringResource(R.string.cat_allergy)
        "جلدية", "Skin Care" -> stringResource(R.string.cat_skin)
        "عين", "Eye Care" -> stringResource(R.string.cat_eye)
        "فيتامينات ومكملات", "Vitamins & Supplements" -> stringResource(R.string.cat_vitamins)
        "إسعافات أولية", "First Aid" -> stringResource(R.string.cat_first_aid)
        "أدوية مزمنة", "Chronic Medications" -> stringResource(R.string.cat_chronic)
        "تصنيف مخصص", "Custom Category" -> stringResource(R.string.cat_custom)
        else -> this.name
    }
}
