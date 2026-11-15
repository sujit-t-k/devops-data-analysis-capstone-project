# This directory should hold these files:
 - rsa_key.p8
 - rsa_key.pub

# Generate RSA Key Pair on Windows

This guide explains how to generate an RSA private key and public key on Windows using OpenSSL.

## 1. Prerequisites

Git for Windows must be installed.

Open PowerShell and verify OpenSSL:

```powershell
& "C:\Program Files\Git\usr\bin\openssl.exe" version
```

## 2. Create a directory for the keys

```powershell
New-Item -ItemType Directory -Force "$HOME\.snowflake\keys"
Set-Location "$HOME\.snowflake\keys"
```

## 3. Generate the RSA private key

Generate a 2048-bit RSA private key in PKCS#8 format:

```powershell
& "C:\Program Files\Git\usr\bin\openssl.exe" genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:2048 -out rsa_key.p8
```

## 4. Generate the RSA public key

Generate the corresponding public key from the private key:

```powershell
& "C:\Program Files\Git\usr\bin\openssl.exe" pkey -in rsa_key.p8 -pubout -out rsa_key.pub
```

## 5. Verify the generated files

```powershell
Get-ChildItem
```

You should see:

```text
rsa_key.p8
rsa_key.pub
```

| File | Description |
|---|---|
| `rsa_key.p8` | RSA private key (PKCS#8 format) |
| `rsa_key.pub` | RSA public key (PEM format) |

## 6. Important security precautions

- Keep `rsa_key.p8` private and secure.
- Never commit the private key to GitHub or any source-control repository.
- Never share the private key with anyone.
- Register only `rsa_key.pub` with Snowflake.
- Store the private key outside your application source directory.