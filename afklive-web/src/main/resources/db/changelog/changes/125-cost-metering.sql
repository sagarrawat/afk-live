--liquibase formatted sql

--changeset afklive:125-transfer-alerts
--comment: FO-5: admins are told once per month when outbound traffic passes 70% and 90% of the included transfer.
--comment: The (month, threshold) key is the claim: with several web instances, only the one whose insert lands sends it.
--comment: New table only: the previous release ignores it.
CREATE TABLE transfer_alerts (
    month VARCHAR(7) NOT NULL,
    threshold INTEGER NOT NULL,
    gb_sent DOUBLE PRECISION NOT NULL,
    included_gb DOUBLE PRECISION NOT NULL,
    sent_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    PRIMARY KEY (month, threshold)
);

--changeset afklive:126-playout-copy-size
--comment: FO-6: a copy's size, so admin can show what copies take up in storage. Written when a copy is made (web side);
--comment: older copies are filled in by the web sweeper from storage. Expand only: nullable, the previous release ignores it.
ALTER TABLE media_playout_copies ADD COLUMN size_bytes BIGINT;
