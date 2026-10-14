package com.example.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.R
import com.example.ui.viewmodels.AppViewModel
import java.text.SimpleDateFormat
import java.util.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MedicineListScreen(
    filter: String,
    onBack: () -> Unit,
    onMedicineClick: (Int) -> Unit,
    viewModel: AppViewModel = viewModel()
) {
    val allMedicines by viewModel.allMedicines.collectAsState()
    val availableItems by viewModel.availableItems.collectAsState()
    val expiredItems by viewModel.expiredItems.collectAsState()
    val currentTime = viewModel.nowTime
    val thirtyDaysMillis = 30L * 24 * 60 * 60 * 1000

    val filteredMedicines = remember(allMedicines, availableItems, expiredItems, filter) {
        val targetItems = when (filter) {
            "expiring" -> availableItems.filter { (it.expiryDate - currentTime) <= thirtyDaysMillis }
            "expired" -> expiredItems
            else -> (availableItems + expiredItems)
        }
        val targetMedIds = targetItems.map { it.medicineId }.toSet()
        if (filter == "total") {
            allMedicines
        } else {
            allMedicines.filter { it.id in targetMedIds }
        }
    }

    val itemsByMedicineId = remember(availableItems, expiredItems) {
        (availableItems + expiredItems).groupBy { it.medicineId }
    }

    val title = when (filter) {
        "expiring" -> stringResource(R.string.expiring_soon)
        "expired" -> stringResource(R.string.expired_medicines)
        else -> stringResource(R.string.total_medicines)
    }

    val dateFormatter = remember { SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()) }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    titleContentColor = MaterialTheme.colorScheme.onSurface,
                    navigationIconContentColor = MaterialTheme.colorScheme.onSurface
                ),
                title = { Text(title, fontWeight = FontWeight.Bold, fontSize = 18.sp) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                    }
                }
            )
        }
    ) { padding ->
        if (filteredMedicines.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        Icons.Outlined.Inventory2,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                        modifier = Modifier.size(48.dp)
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    Text(
                        stringResource(R.string.no_medicines_yet),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Medium
                    )
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(filteredMedicines) { medicine ->
                    val minExpiryDate = itemsByMedicineId[medicine.id]?.minOfOrNull { it.expiryDate }

                    val (badgeText, badgeBg, badgeColor) = when {
                        minExpiryDate == null -> Triple("—", MaterialTheme.colorScheme.surfaceVariant, MaterialTheme.colorScheme.onSurfaceVariant)
                        minExpiryDate < currentTime -> Triple(stringResource(R.string.expired_badge), Color(0xFFFEE2E2), Color(0xFF991B1B))
                        (minExpiryDate - currentTime) <= thirtyDaysMillis -> {
                            val days = ((minExpiryDate - currentTime) / (24 * 60 * 60 * 1000)).toInt().coerceAtLeast(0)
                            Triple("$days d", Color(0xFFFEF3C7), Color(0xFF92400E))
                        }
                        else -> Triple(stringResource(R.string.valid_badge), Color(0xFFD1FAE5), Color(0xFF065F46))
                    }

                    val formIcon = when (medicine.form.lowercase().trim()) {
                        "tablets", "أقراص", "capsules", "كبسولات", "comprimés", "gélules", "tabletten", "kapseln" -> Icons.Outlined.Medication
                        "syrup", "شراب", "sirop", "sirup / saft", "sirup", "saft" -> Icons.Outlined.WaterDrop
                        "cream", "مرهم / كريم", "crème / pommade", "crème", "creme / salbe", "creme", "salbe" -> Icons.Outlined.Healing
                        "spray", "بخاخ" -> Icons.Outlined.Air
                        "drops", "قطرات", "gouttes", "tropfen" -> Icons.Outlined.Opacity
                        "injections", "حقن", "injektionen" -> Icons.Outlined.Vaccines
                        else -> Icons.Outlined.MedicalServices
                    }

                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onMedicineClick(medicine.id) },
                        shape = RoundedCornerShape(16.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.35f))
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(44.dp)
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(MaterialTheme.colorScheme.primaryContainer),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    formIcon,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                                    modifier = Modifier.size(22.dp)
                                )
                            }

                            Spacer(modifier = Modifier.width(14.dp))

                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    medicine.tradeName,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 16.sp,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                if (!medicine.scientificName.isNullOrBlank()) {
                                    Spacer(modifier = Modifier.height(2.dp))
                                    Text(
                                        medicine.scientificName,
                                        fontSize = 13.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }

                            Spacer(modifier = Modifier.width(8.dp))

                            Column(horizontalAlignment = Alignment.End) {
                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(12.dp))
                                        .background(badgeBg)
                                        .padding(horizontal = 9.dp, vertical = 4.dp)
                                ) {
                                    Text(
                                        text = badgeText,
                                        color = badgeColor,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 11.sp
                                    )
                                }
                                if (minExpiryDate != null) {
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Text(
                                        dateFormatter.format(Date(minExpiryDate)),
                                        fontSize = 11.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
