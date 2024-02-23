#!/bin/bash

# Create the "assets" directory if it doesn't exist
mkdir -p assets

# Package the chart into a temporary directory
helm package . -d temp

# Create or update the index file for the chart repository
helm repo index --url assets --merge index.yaml temp

# Move the packaged chart and index file to their final locations
mv temp/bcc-ui-chart-*.tgz assets
mv temp/index.yaml .

# Remove the temporary directory
rm -rf temp
