# Bluetooth remote signer

The name of this Bluetooth signer is zcinti.

This document describes the Bluetooth remote signer.
The words are simple. The names of the parts stay exact.
This stack signs blocks for the temporary node on this laptop.
It does not sign for the live Morocco signer.

## 1. Parts

The phone holds the consensus key.
The phone is the Zigner app.
The laptop runs the KMS console at `http://127.0.0.1:8791`.
The laptop scanner listens for a short pairing code.
The temporary node is a local process. It is not the live file-backed signer.
The temporary node asks the KMS console to sign.
The KMS console asks the phone to sign.
The phone returns the signature.
The KMS console checks the signature.
Then the KMS console answers the node.

The live Morocco signer uses a file on the laptop.
Do not point that signer at the phone.

## 2. What the user sees

The wallet home screen has two tabs.
The tabs are **Keys** and **Signer**.
The Signer tab shows the last signed block.
The Signer tab shows the latency of that signature.
Latency is the time from the laptop request to the phone signature.
The same facts are on the Bluetooth signer screen.
The laptop page keeps the last 100 signatures.

## 3. How a session starts

1. The user opens the Signer tab, or the Bluetooth signer screen.
2. The user taps **Start signer** or **Pair**.
3. The phone asks for Bluetooth permission and notification permission.
4. The phone starts a foreground service.
5. Android shows one ongoing notification. The title is "Remote signer".
6. The phone advertises a 4-byte pairing code. The phone does not send its name.
7. The laptop scanner shows that code.
8. The user taps **Accept** on the laptop page.
9. The laptop connects to the phone.
10. The phone gives the laptop the public key.
11. The KMS console dials the temporary node.
12. The node can now request signatures.

The phone keeps the same pairing code for the next start.
The user does not need a new code after the screen changes.

## 4. How one block is signed

1. The temporary node sends a sign request to the KMS console.
2. The request contains a height, a round, a step, and the block bytes.
3. The KMS console sends those bytes to the phone over Bluetooth.
4. The phone signs the block bytes with the Ed25519 key.
5. The phone does not use key size 256. Ed25519 does not accept that size.
6. The phone sends the signature back.
7. The KMS console checks the signature against the public key.
8. The KMS console returns the signature to the node.
9. The phone and the laptop add one row to the log.
10. The row contains the height, the round, the step, the latency, and the time.

A good signature lets the temporary node move to the next block.
No signature means the temporary node does not move.

## 5. How long the connection stays

The connection stays when the user leaves the Signer screen.
The connection stays when the user leaves the Bluetooth signer screen.
The connection stays when the user closes the activity.
The foreground service keeps the radio on.
The notification stays on the phone.
This is the same pattern as the bitchat mesh service.

The connection stops in these cases:

- The user taps **Stop signer**.
- The user taps **Stop** on the notification.
- The system destroys the service.
- The user force-stops the app.

A force-stop ends the signer. Android does not let an app ignore a force-stop.
After a normal start of the app, the service starts again if the signer was armed.

## 6. Safety limits

The phone does not open the laptop web page.
The app has no Internet permission.
The phone does not turn the Bluetooth radio on or off by itself.
The phone does not read the system Bluetooth device list.
The laptop log does not contain the private key.
The temporary node cannot sign from the key file on disk while the phone session is armed.
The key file on that node is moved aside. Do not print that file.

## 7. Fresh validator

Use these steps in this order.

1. Create the consensus key on the phone, or scan a seed QR into the phone.
   The KMS page does not display that seed.
2. Connect the phone to KMS with the pairing code.
   Leave the zcinti service running.
3. Prepare the transaction on the cw-orch page.
   That page builds the authz grant, the feegrant, and the create-validator message.
   An ephemeral wallet broadcasts the transaction.
   The phone signs the bytes.
   The phone does not broadcast.

## 8. Figure list

Use these figures. Draw only the behavior in this document.

1. The four parts and the path of one signature.
   Show the phone, the laptop scanner, the KMS console, and the temporary node.
   Show that the live Morocco signer is outside this path.
2. The life of the phone session.
   Show: start, leave the screen, close the activity, notification still on, stop, force-stop.
3. One block, from the node request to the next block.
   Show the latency measurement on that path.
4. The Signer tab.
   Show the block height and the latency. Do not invent numbers.
   You can label the fields. You cannot label a measured result unless this document gives it.
