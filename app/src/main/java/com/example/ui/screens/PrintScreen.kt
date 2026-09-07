package com.example.ui.screens

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.R
import com.example.ui.viewmodels.AppViewModel
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PrintScreen(
    onBack: () -> Unit,
    viewModel: AppViewModel = viewModel()
) {
    val context = LocalContext.current
    val allMedicines by viewModel.allMedicines.collectAsState()
    val availableItems by viewModel.availableItems.collectAsState()
    val expiredItems by viewModel.expiredItems.collectAsState()

    var exportMessage by remember { mutableStateOf<String?>(null) }
    val successText = stringResource(R.string.export_success)
    val errorText = stringResource(R.string.export_error)

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    titleContentColor = MaterialTheme.colorScheme.onSurface,
                    navigationIconContentColor = MaterialTheme.colorScheme.onSurface
                ),
                title = { Text(stringResource(R.string.export_print_title), fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.back)
                        )
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // CSV Export Card
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Default.Description,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(36.dp)
                        )
                        Spacer(modifier = Modifier.width(16.dp))
                        Column {
                            Text(
                                stringResource(R.string.export_csv_btn),
                                style = MaterialTheme.typography.titleMedium.copy(
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            )
                            Text(
                                stringResource(R.string.export_csv_desc),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f)
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(16.dp))
                    Button(
                        shape = RoundedCornerShape(12.dp),
                        onClick = {
                            try {
                                exportToCsv(context, allMedicines, availableItems + expiredItems)
                                exportMessage = successText
                            } catch (e: Exception) {
                                exportMessage = "$errorText: ${e.message}"
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                    ) {
                        Text(stringResource(R.string.export_csv_btn), color = MaterialTheme.colorScheme.onPrimary)
                    }
                }
            }

            // PDF Export Card
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Default.PictureAsPdf,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(36.dp)
                        )
                        Spacer(modifier = Modifier.width(16.dp))
                        Column {
                            Text(
                                stringResource(R.string.export_pdf_btn),
                                style = MaterialTheme.typography.titleMedium.copy(
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            )
                            Text(
                                stringResource(R.string.export_pdf_desc),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f)
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(16.dp))
                    Button(
                        shape = RoundedCornerShape(12.dp),
                        onClick = {
                            try {
                                exportToPdf(context, allMedicines, availableItems + expiredItems)
                                exportMessage = successText
                            } catch (e: Exception) {
                                exportMessage = "$errorText: ${e.message}"
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                    ) {
                        Text(stringResource(R.string.export_pdf_btn), color = MaterialTheme.colorScheme.onPrimary)
                    }
                }
            }

            exportMessage?.let { msg ->
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = msg,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }
    }
}

fun exportToCsv(context: Context, medicines: List<com.example.data.Medicine>, items: List<com.example.data.InventoryItem>) {
    val file = File(context.cacheDir, "medicines_inventory.csv")
    val writer = file.bufferedWriter()
    writer.write("Trade Name,Scientific Name,Strength,Form,Quantity,Expiry Date,Storage Location\n")
    val sdf = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
    for (med in medicines) {
        val item = items.find { it.medicineId == med.id }
        val exp = item?.expiryDate?.let { sdf.format(Date(it)) } ?: ""
        val qty = item?.quantity ?: 0.0
        val loc = item?.storageLocation ?: ""
        writer.write("\"${med.tradeName}\",\"${med.scientificName ?: ""}\",\"${med.strength ?: ""}\",\"${med.form}\",\"$qty\",\"$exp\",\"$loc\"\n")
    }
    writer.close()
    shareFile(context, file, "text/csv")
}

fun exportToPdf(context: Context, medicines: List<com.example.data.Medicine>, items: List<com.example.data.InventoryItem>) {
    val file = File(context.cacheDir, "medicines_inventory.txt")
    val fos = FileOutputStream(file)
    val sdf = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
    val content = StringBuilder("--- Medicine Inventory Report ---\n\n")
    for (med in medicines) {
        val item = items.find { it.medicineId == med.id }
        val exp = item?.expiryDate?.let { sdf.format(Date(it)) } ?: "N/A"
        content.append("• ${med.tradeName} (${med.scientificName ?: "N/A"})\n")
        content.append("  Qty: ${item?.quantity ?: 0} | Expiry: $exp | Location: ${item?.storageLocation ?: "N/A"}\n\n")
    }
    fos.write(content.toString().toByteArray())
    fos.close()
    shareFile(context, file, "text/plain")
}

fun shareFile(context: Context, file: File, mimeType: String) {
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.provider", file)
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = mimeType
        putExtra(Intent.EXTRA_STREAM, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(Intent.createChooser(intent, "Share File"))
}
