sed -i 's/import com.example.applet.R//g' app/src/main/java/com/example/ui/screens/DashboardScreen.kt
sed -i 's/label = "الأدوية المسجلة"/label = stringResource(R.string.total_medicines)/g' app/src/main/java/com/example/ui/screens/DashboardScreen.kt
sed -i 's/label = "تنتهي خلال 30 يومًا"/label = stringResource(R.string.expiring_soon)/g' app/src/main/java/com/example/ui/screens/DashboardScreen.kt
sed -i 's/label = "منتهية الصلاحية"/label = stringResource(R.string.expired_medicines)/g' app/src/main/java/com/example/ui/screens/DashboardScreen.kt
sed -i 's/label = "مسح باركود"/label = stringResource(R.string.scan_barcode)/g' app/src/main/java/com/example/ui/screens/DashboardScreen.kt
sed -i 's/label = "طباعة قائمة"/label = stringResource(R.string.export_print)/g' app/src/main/java/com/example/ui/screens/DashboardScreen.kt
