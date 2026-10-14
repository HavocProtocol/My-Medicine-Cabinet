import re

with open('app/src/main/java/com/example/ui/screens/AISearchScreen.kt', 'r') as f:
    content = f.read()

replacement = """        } else {
            if (response.code == 429) {
                "عذراً، لقد تجاوزت الحد المسموح به لطلبات الذكاء الاصطناعي (Quota Exceeded). يرجى التحقق من مفتاح API أو تحديث خطة الفوترة الخاصة بك."
            } else {
                try {
                    val errorJson = JSONObject(responseBody ?: "{}").optJSONObject("error")
                    val errorMessage = errorJson?.optString("message") ?: "خطأ غير معروف"
                    "حدث خطأ (${response.code}): $errorMessage"
                } catch (e: Exception) {
                    "حدث خطأ (${response.code}): $responseBody"
                }
            }
        }"""

content = re.sub(r'        \} else \{\s*"Error: \$\{response\.code\} - \$responseBody"\s*\}', replacement, content)

with open('app/src/main/java/com/example/ui/screens/AISearchScreen.kt', 'w') as f:
    f.write(content)
