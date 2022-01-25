#!/bin/bash

echo "This shows the output of 'jbang mdns.java':"
echo ""
jbang ../../mdns.java -t 30
echo ""
echo "30 second timeout reached. Closing connection."
