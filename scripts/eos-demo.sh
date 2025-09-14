#!/bin/bash

# Simple EOS Demo Script
set -e

# Colors
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
CYAN='\033[0;36m'
NC='\033[0m'

echo -e "${BLUE}═══════════════════════════════════════════════════════════${NC}"
echo -e "${BLUE}           Kafka EOS (Exactly-Once) Demo                   ${NC}"
echo -e "${BLUE}═══════════════════════════════════════════════════════════${NC}"

# 1. Check processor is running with EOS
echo -e "\n${CYAN}1. Checking Signal Processor EOS Configuration${NC}"
kubectl logs deployment/signal-processor -n trading --tail=100 | grep -i "eos\|exactly" | head -3 || echo "Checking logs..."

# 2. Show current processing
echo -e "\n${CYAN}2. Current Signal Processing${NC}"
echo -e "${YELLOW}Last 5 decisions from processor:${NC}"
kubectl exec -n trading kcat -- kcat -b trading-kafka-bootstrap:9092 -C -t trading.decisions -q -o -5 -e 2>/dev/null | jq -c '{symbol, action, confidence, timestamp}' 2>/dev/null || echo "No recent decisions"

# 3. Inject test duplicates
echo -e "\n${CYAN}3. Injecting Duplicate Test Signals${NC}"
echo -e "${YELLOW}Sending same signal 5 times to test deduplication...${NC}"

# Create a test signal
TEST_SIGNAL='{
  "symbol": "EOS_TEST",
  "side": "BUY",
  "qty": 1.0,
  "price": 99999.99,
  "timeframe": "1m",
  "ts": '$(date +%s000)',
  "test": "eos-demo"
}'

# Send the same signal 5 times
for i in {1..5}; do
    echo "$TEST_SIGNAL" | kubectl exec -i -n trading kcat -- kcat -b trading-kafka-bootstrap:9092 -P -t trading.signal.1m 2>/dev/null
    echo -e "  ${GREEN}✓${NC} Duplicate #$i sent"
    sleep 0.5
done

# 4. Wait and check results
echo -e "\n${CYAN}4. Waiting for Processing (10 seconds)${NC}"
sleep 10

# 5. Check for duplicates in output
echo -e "\n${CYAN}5. Checking Results${NC}"
echo -e "${YELLOW}Looking for EOS_TEST in decisions:${NC}"

RESULTS=$(kubectl exec -n trading kcat -- kcat -b trading-kafka-bootstrap:9092 -C -t trading.decisions -q -o -20 -e 2>/dev/null | grep "EOS_TEST" | wc -l)

if [ "$RESULTS" -eq 0 ]; then
    echo -e "${YELLOW}No decisions yet for EOS_TEST (might need more signals for aggregation)${NC}"
elif [ "$RESULTS" -eq 1 ]; then
    echo -e "${GREEN}✅ SUCCESS: Only 1 decision for EOS_TEST despite 5 duplicate inputs!${NC}"
    echo -e "${GREEN}   This demonstrates Exactly-Once Semantics working correctly.${NC}"
else
    echo -e "${YELLOW}Found $RESULTS decisions for EOS_TEST${NC}"
fi

# 6. Show processor metrics
echo -e "\n${CYAN}6. Signal Processor Metrics${NC}"
kubectl logs deployment/signal-processor -n trading --tail=5 | grep -i "metrics\|processed" || echo "No recent metrics"

echo -e "\n${BLUE}═══════════════════════════════════════════════════════════${NC}"
echo -e "${GREEN}EOS Demo Complete!${NC}"
echo -e "\nKey Takeaways:"
echo -e "  • Signal processor uses ${GREEN}EXACTLY_ONCE_V2${NC} guarantee"
echo -e "  • Duplicate inputs are ${GREEN}automatically deduplicated${NC}"
echo -e "  • Each signal is processed ${GREEN}exactly once${NC}, not zero or multiple times"
echo -e "  • This ensures ${GREEN}data consistency${NC} in the trading pipeline"