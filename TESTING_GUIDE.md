# Quick Testing Guide - Token Payment System

## Prerequisites
- Two Android devices (one for merchant, one for payer)
- Bluetooth enabled on both devices
- All permissions granted
- Location services enabled (required for BLE on some devices)

## Step-by-Step Test Flow

### Phase 1: Initial Setup

#### Device 1 (Payer):
1. Install and launch the app
2. **Expected**: Permission request dialog appears
3. Grant ALL permissions when prompted
4. **Expected**: Bluetooth enable dialog appears (if BT was off)
5. Enable Bluetooth
6. **Expected**: Main screen shows "Balance: Rs 2500.00"
7. Verify in logs: "Minted token: Rs 500 (Token 1 of 3)" etc.

#### Device 2 (Merchant):
1. Install and launch the app
2. Grant all permissions
3. Enable Bluetooth
4. **Expected**: Main screen shows "Balance: Rs 2500.00"

---

### Phase 2: Merchant Setup

#### On Merchant Device:
1. Click "Merchant Mode"
2. **Expected**: Screen shows:
   - "POS ID: [8-character ID]"
   - "Balance: Rs 2500.00"
   - "Ready to accept payments"
3. Click "Display QR Code"
4. **Expected**: 
   - QR code appears on screen
   - Status changes to "Advertising... Show QR to payer"
5. **In Logs**: Look for:
   ```
   ✓ Advertising started successfully for service: com.example.cbdc.CBDC_SERVICE
   ```

---

### Phase 3: Payment Transaction

#### On Payer Device:
1. Click "Payer Mode"
2. Click "Scan QR Code"
3. Point camera at merchant's QR code
4. **Expected**: QR scans, camera closes
5. **Expected**: Status changes to:
   - "QR Scanned! Establishing BLE connection..."
   - Progress spinner appears

#### What Happens Next (Both Devices):

**Within 5-15 seconds:**

**Merchant Device:**
- Status: "✓ Payer connected! Waiting for payment..."
- Progress spinner appears
- **Logs**: `✓✓ Connection SUCCESSFUL with payer: [endpoint_id]`

**Payer Device:**
- Status: "✓ Connection established! Enter amount to send:"
- Progress spinner disappears
- **Dialog pops up**: "Enter Amount to Send"
- **Logs**: 
  ```
  ✓✓ Connection SUCCESSFUL with: [endpoint_id]
  ✓ Session key established via ECDH
  ```

---

### Phase 4: Send Payment

#### On Payer Device:
1. In the amount dialog, enter: **150**
2. Click "Send"
3. **Expected Status Updates**:
   - "Sending payment of Rs 150..."
   - "Payment sent, waiting for confirmation..."
   - "✓ Payment accepted!"
4. **Expected**: 
   - Toast: "Payment successful!"
   - Balance updates to: "Rs 2350.00" (2500 - 150)
   - Screen closes after 2 seconds

#### On Merchant Device:
1. **Expected Status Updates**:
   - "✓ Payment received! Rs 150"
   - Progress spinner disappears
   - Balance updates to: "Rs 2650.00" (2500 + 150)
2. **Expected Toast**: "Received Rs 150"

#### In Logs:

**Payer Logs:**
```
→ Sending pending payment after key exchange
Token transfer sent
✓ Payment ACCEPTED by merchant
```

**Merchant Logs:**
```
← Received encrypted payment message
Processing TOKEN_TRANSFER message
Token: [UUID], Amount: Rs 150.0
✓ Transfer signature verified
✓ Token stored in merchant wallet
✓✓ Payment completed successfully: Rs 150.0
```

---

### Phase 5: Verify Token Transfer

#### Check Payer Wallet:
- Balance should be Rs 2350.00
- Rs 100 token deleted
- Rs 50 token deleted

#### Check Merchant Wallet:
- Balance should be Rs 2650.00
- Rs 100 token added (same ID as deleted from payer)
- Rs 50 token added (same ID as deleted from payer)

---

## Test Cases

### Test Case 1: Simple Payment (Rs 150)
- **Input**: Rs 150
- **Expected Tokens**: 100 + 50
- **Payer Balance**: 2500 → 2350
- **Merchant Balance**: 2500 → 2650
- **Result**: ✓ PASS / ✗ FAIL

### Test Case 2: Exact Single Token (Rs 500)
- **Input**: Rs 500
- **Expected Tokens**: 500
- **Payer Balance**: 2350 → 1850
- **Merchant Balance**: 2650 → 3150
- **Result**: ✓ PASS / ✗ FAIL

### Test Case 3: Multiple Small Tokens (Rs 23)
- **Input**: Rs 23
- **Expected Tokens**: 20 + 2 + 1
- **Payer Balance**: 1850 → 1827
- **Merchant Balance**: 3150 → 3173
- **Result**: ✓ PASS / ✗ FAIL

### Test Case 4: Invalid Amount (Rs 999)
- **Input**: Rs 999
- **Expected**: Error "Insufficient balance or cannot make exact amount"
- **Balances**: No change
- **Result**: ✓ PASS / ✗ FAIL

### Test Case 5: Connection Timeout
- **Setup**: Turn off Bluetooth on merchant AFTER payer scans QR
- **Expected**: "Connection timeout. Please ensure merchant device is nearby and try again."
- **Result**: ✓ PASS / ✗ FAIL

### Test Case 6: Permission Denied
- **Setup**: Deny Bluetooth permission
- **Expected**: Dialog requesting permissions
- **Result**: ✓ PASS / ✗ FAIL

---

## Common Issues and Solutions

### Issue: "Connection timeout"
**Cause**: Devices too far apart, Bluetooth interference
**Solution**: 
- Bring devices within 1-2 meters
- Ensure both have Bluetooth enabled
- Check logs for advertising/discovery status

### Issue: "Cannot make exact amount"
**Cause**: Insufficient tokens with required denominations
**Solution**: 
- Check available tokens in wallet
- Try smaller amount
- Reset app data to regenerate initial Rs 2500

### Issue: "Permission denied"
**Cause**: User denied one or more permissions
**Solution**: 
- Go to Settings → Apps → [App Name] → Permissions
- Enable all required permissions
- Restart app

### Issue: QR scan doesn't trigger connection
**Cause**: Camera permission not granted, or QR parsing failed
**Solution**: 
- Check camera permission
- Ensure good lighting for QR scan
- Check logs for "QR Parser returned null"

### Issue: Merchant not found
**Cause**: Merchant not advertising or BLE discovery failed
**Solution**: 
- Check merchant logs for "✓ Advertising started successfully"
- Ensure Location permission granted (required for BLE scan)
- Try restarting merchant advertising

---

## Debugging Commands

### View Live Logs (Android Studio):
```
Logcat filter: PayerNearbyClient|MerchantNearbyService|TokenManager
```

### Check Bluetooth Status (ADB):
```powershell
adb shell dumpsys bluetooth_manager
```

### Check App Permissions (ADB):
```powershell
adb shell dumpsys package com.example.cbdc | Select-String "granted=true"
```

### Clear App Data (Reset to Rs 2500):
```powershell
adb shell pm clear com.example.cbdc
```

---

## Expected Log Patterns

### Successful Payment Flow:
```
[Payer] ✓ Discovery started successfully
[Merchant] ✓ Advertising started successfully
[Payer] ✓ Endpoint found: CBDC-Merchant-[ID]
[Payer] ✓✓ Connection SUCCESSFUL
[Merchant] ✓✓ Connection SUCCESSFUL with payer
[Payer] → Sent our ephemeral public key
[Merchant] ← Received payer's ephemeral public key
[Merchant] ✓ Session key established
[Payer] ← Received merchant's ephemeral public key
[Payer] ✓ Session key established via ECDH
[Payer] → Sending pending payment
[Merchant] ← Received encrypted payment message
[Merchant] ✓ Transfer signature verified
[Merchant] ✓ Token stored in merchant wallet
[Merchant] → Sent ACCEPT receipt
[Payer] ← Received encrypted message (ACCEPT receipt)
[Payer] ✓ Payment ACCEPTED by merchant
```

### Failed Connection:
```
[Payer] ✓ Discovery started successfully
[Payer] ⚠ Connection timeout after 15 seconds
[Payer] ✗ Discovery failed: [reason]
```

---

## Performance Benchmarks

- **QR Scan Time**: 1-3 seconds
- **BLE Discovery**: 2-5 seconds
- **Connection Establishment**: 1-2 seconds
- **Key Exchange**: 0.5-1 second
- **Payment Transfer**: 0.5-1 second
- **Total Transaction Time**: 5-12 seconds

---

## Success Criteria

✓ All permissions granted automatically
✓ Bluetooth enables on request
✓ Initial Rs 2500 balance appears
✓ QR scan triggers immediate connection
✓ Connection establishes within 15 seconds
✓ Amount dialog pops automatically after connection
✓ Correct token denominations selected
✓ Payment completes successfully
✓ Balances update on both devices
✓ Same token IDs transferred from payer to merchant
✓ Clear status messages throughout
✓ Comprehensive error messages on failures

---

## Reset for Next Test

### Quick Reset (Keep App):
```powershell
adb shell pm clear com.example.cbdc
```

### Full Reset:
1. Uninstall app from both devices
2. Reinstall
3. Grant all permissions
4. Verify Rs 2500 balance on both

---

## Support Contacts

- Check `IMPLEMENTATION_SUMMARY.md` for detailed architecture
- Review `BLE_ARCHITECTURE_COMPARISON.md` for connection details
- See `PROJECT_DOCUMENTATION.md` for overall system design
