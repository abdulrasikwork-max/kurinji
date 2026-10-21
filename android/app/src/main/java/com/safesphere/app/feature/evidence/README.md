# feature/evidence (Phase 5: Emergency Evidence Vault)

- `domain/`: models, `EvidenceRepository`, `RecordingPolicy` (pure)
- `data/`: `EvidenceFiles` (on-phone folders), index file, `RemoteEvidenceRepository` + `remote/` API/DTOs
- `capture/`: `EvidenceRecorder` (the one audio holder), `MediaRecorderSegmentWriter` (AAC/ADTS parts)
- `session/`: `EvidenceVault` (the one holder: seal, recover, upload, delete, view)
- `ui/`: SOS Evidence card, Evidence Vault screen

Crypto lives in `core/crypto` (`EvidenceCipher` = SSEV1, Keystore wrapper, escrow key). Design and limits:
`docs/PHASE5.md`.
