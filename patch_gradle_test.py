with open('app/build.gradle.kts', 'r') as f:
    content = f.read()

replacement = """  testOptions {
    unitTests {
      isIncludeAndroidResources = true
      all {
        it.testLogging {
          events("passed", "skipped", "failed", "standardOut", "standardError")
        }
      }
    }
  }"""

import re
content = re.sub(r'testOptions \{.*?\}', replacement, content, flags=re.DOTALL)

with open('app/build.gradle.kts', 'w') as f:
    f.write(content)
