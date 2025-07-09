# PDFsigner

PDFsigner is an Android application that demonstrates how to capture a handwritten signature,
embed it into a PDF and create a digital signature in one flow.
It combines **pdfbox-android** for manipulating PDF documents,
**android-pdf-viewer** for displaying them and **Bouncy Castle** for PAdES signing.

## Features

- Load a PDF from storage and preview it.
- Capture a signature with pressure and timing data.
- Place and scale the captured signature on a page.
- Embed the signature bitmap into the PDF.
- Store the biometric JSON data in the PDF metadata and in the digital signature as an ISO/IEC 19794‑7 biometric data block.
- Digitally sign the document using a key from the Android KeyStore.

## Building

This project uses the Gradle wrapper. To build it, run:

```bash
./gradlew assembleDebug
```

Unit tests can be executed with:

```bash
./gradlew test
```

_Note_: Internet access is required on the first run in order to download dependencies.

## Modules

- `app` – the main Android application.
- `android-pdf-viewer` – a local copy of the PDF viewer library.
- `sample` – a sample activity for the viewer library (not needed for signing).

## License

No explicit license file is provided in this repository. The code is
intended for demonstration purposes.
