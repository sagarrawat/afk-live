#!/bin/bash
find src/components -type f -name "*.jsx" | while read -r file; do
  sed -i '' -E 's/([^a-zA-Z0-9_-])bg-white([^a-zA-Z0-9_-])/\1bg-white dark:bg-[#1a1825]\2/g' "$file"
  sed -i '' -E 's/([^a-zA-Z0-9_-])bg-gray-50([^a-zA-Z0-9_-])/\1bg-gray-50 dark:bg-[#0d0b13]\2/g' "$file"
  sed -i '' -E 's/([^a-zA-Z0-9_-])bg-gray-100([^a-zA-Z0-9_-])/\1bg-gray-100 dark:bg-[#221f30]\2/g' "$file"
  sed -i '' -E 's/([^a-zA-Z0-9_-])text-gray-900([^a-zA-Z0-9_-])/\1text-gray-900 dark:text-white\2/g' "$file"
  sed -i '' -E 's/([^a-zA-Z0-9_-])text-gray-800([^a-zA-Z0-9_-])/\1text-gray-800 dark:text-gray-200\2/g' "$file"
  sed -i '' -E 's/([^a-zA-Z0-9_-])text-gray-700([^a-zA-Z0-9_-])/\1text-gray-700 dark:text-gray-300\2/g' "$file"
  sed -i '' -E 's/([^a-zA-Z0-9_-])text-gray-600([^a-zA-Z0-9_-])/\1text-gray-600 dark:text-gray-400\2/g' "$file"
  sed -i '' -E 's/([^a-zA-Z0-9_-])border-gray-200([^a-zA-Z0-9_-])/\1border-gray-200 dark:border-gray-700\2/g' "$file"
  sed -i '' -E 's/([^a-zA-Z0-9_-])border-gray-100([^a-zA-Z0-9_-])/\1border-gray-100 dark:border-gray-800\2/g' "$file"
done
