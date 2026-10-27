# Bulk SMS

Android app that sends a personalised SMS to a list of people from your phone's own SIM. It uses the same design as [SMS Relay](https://github.com/mulualeme/sms-relay).

## How it works

1. **Recipients.** Import an Excel workbook (`.xlsx`, first sheet), or a CSV, TSV or TXT file. The app finds the phone number and name columns, and you can change them. Invalid and duplicate numbers are skipped.
2. **Message.** Write the text and tap a column to insert it: `{First Name}`, `{Name}`, or any column from your file, such as `{City}`. "Hi {First Name}" becomes "Hi Abebe" for Abebe Kebede. You can preview each person's message and its SMS count. An opt-out line ("Reply STOP to opt out") is added by default.
3. **Send.** Pick the SIM and the pause between messages. A foreground service sends one message at a time and waits for the network's result, so sending continues with the screen off. Each recipient is marked Sent or Failed, with the reason. You can pause, resume and retry failures, and a campaign interrupted by a restart can be resumed.

## Things to know

- **Carrier charges.** Every message is a normal SMS from your SIM, billed at your plan's rate.
- **Android's sending limit.** Android asks for confirmation after an app sends 30 messages within 30 minutes. Choose **Always allow**. For a phone you control, you can also raise the limit with adb: `adb shell settings put global sms_outgoing_check_max_count 1000`.
- **Leading zeros in Excel.** Phone numbers stored as numbers in Excel lose their leading 0. Format the column as text, or use the international format (`+251…`).
- **Consent.** Only message people who agreed to hear from you. In many countries, advertising SMS requires consent and an opt-out. Sending fast to many strangers can also get the SIM blocked by your carrier.

## Build

```
./gradlew testDebugUnitTest assembleDebug
```

Release signing uses the same `SIGNING_*` environment variables and GitHub secrets as SMS Relay (see `.github/workflows/build.yml`).
