import re
with open('app/build.gradle.kts', 'r') as f:
    lines = f.readlines()

if lines[-1].strip() != '}':
    with open('app/build.gradle.kts', 'a') as f:
        f.write('\n}\n')
