with open('app/src/main/java/com/example/ui/viewmodels/AppViewModel.kt', 'r') as f:
    lines = f.readlines()

new_lines = []
skip = False
for i, line in enumerate(lines):
    if "private fun checkAndGenerateAlerts()" in line and "{" not in line:
        new_lines.append("    private fun checkAndGenerateAlerts() {\n")
        skip = True
    elif skip and "} {" in line:
        skip = False
    elif not skip:
        new_lines.append(line)

with open('app/src/main/java/com/example/ui/viewmodels/AppViewModel.kt', 'w') as f:
    f.writelines(new_lines)
