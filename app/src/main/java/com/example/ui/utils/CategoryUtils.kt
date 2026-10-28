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
    val n = name.lowercase().trim()
    return when {
        n.contains("صدر") || n.contains("تنفس") || n.contains("respirat") || n.contains("atemweg") -> stringResource(R.string.cat_respiratory)
        n.contains("بطن") || n.contains("هضم") || n.contains("gastro") || n.contains("magen") -> stringResource(R.string.cat_gastro)
        n.contains("أنف") || n.contains("أذن") || n.contains("حنجرة") || n.contains("ent") || n.contains("orl") || n.contains("hno") -> stringResource(R.string.cat_ent)
        n.contains("مسكن") || n.contains("خافض") || n.contains("pain") || n.contains("antidouleur") || n.contains("schmerz") || n.contains("antipyrét") -> stringResource(R.string.cat_painkillers)
        n.contains("مضاد") || n.contains("حيوي") || n.contains("antibiot") -> stringResource(R.string.cat_antibiotics)
        n.contains("حساس") || n.contains("allerg") -> stringResource(R.string.cat_allergy)
        n.contains("جلد") || n.contains("dermat") || n.contains("skin") || n.contains("peau") || n.contains("haut") -> stringResource(R.string.cat_skin)
        n.contains("عين") || n.contains("eye") || n.contains("yeux") || n.contains("augen") || n.contains("ophtal") -> stringResource(R.string.cat_eye)
        n.contains("فيتامين") || n.contains("مكمل") || n.contains("vitamin") || n.contains("complément") || n.contains("nahrungsergänzung") -> stringResource(R.string.cat_vitamins)
        n.contains("إسعاف") || n.contains("first aid") || n.contains("premier") || n.contains("secours") || n.contains("erste hilfe") -> stringResource(R.string.cat_first_aid)
        n.contains("مزمن") || n.contains("chronic") || n.contains("chronique") || n.contains("dauer") -> stringResource(R.string.cat_chronic)
        n.contains("مخصص") || n.contains("custom") || n.contains("personnalis") || n.contains("benutzer") -> stringResource(R.string.cat_custom)
        else -> name
    }
}
