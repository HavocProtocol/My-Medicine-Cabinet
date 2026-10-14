package com.example.ui.utils

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.example.R
import com.example.data.Category

@Composable
fun Category.getLocalizedName(): String {
    return getLocalizedCategoryName(this.name)
}

@Composable
fun getLocalizedCategoryName(name: String): String {
    return when (name.trim()) {
        "صدرية وتنفسية", "Respiratory", "Respiratoire", "Atemwege" -> stringResource(R.string.cat_respiratory)
        "بطنية وهضمية", "Gastrointestinal", "Gastro-intestinal", "Magen-Darm" -> stringResource(R.string.cat_gastro)
        "أنف وأذن وحنجرة", "ENT", "ORL", "HNO", "HNO (Hals-Nasen-Ohren)" -> stringResource(R.string.cat_ent)
        "مسكنات وخافضات حرارة", "مسكنات الألم", "Painkillers & Antipyretics", "Antidouleurs et antipyrétiques", "Schmerzmittel & Fiebersenker", "Schmerzmittel & Antipyretika" -> stringResource(R.string.cat_painkillers)
        "مضادات حيوية", "Antibiotics", "Antibiotiques", "Antibiotika" -> stringResource(R.string.cat_antibiotics)
        "حساسية", "Allergy", "Allergie" -> stringResource(R.string.cat_allergy)
        "جلدية", "Skin Care", "Dermatologie", "Soins de la peau", "Hautpflege" -> stringResource(R.string.cat_skin)
        "عين", "Eye Care", "Soins des yeux", "Augenpflege" -> stringResource(R.string.cat_eye)
        "فيتامينات ومكملات", "فيتامينات", "Vitamins & Supplements", "Vitamines et compléments", "Vitamine & Nahrungsergänzung" -> stringResource(R.string.cat_vitamins)
        "إسعافات أولية", "First Aid", "Premiers secours", "Erste Hilfe" -> stringResource(R.string.cat_first_aid)
        "أدوية مزمنة", "Chronic Medications", "Médicaments pour maladies chroniques", "Dauermedikation" -> stringResource(R.string.cat_chronic)
        "تصنيف مخصص", "Custom Category", "Catégorie personnalisée", "Benutzerdefinierte Kategorie" -> stringResource(R.string.cat_custom)
        else -> name
    }
}
