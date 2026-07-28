-- Escrow return markers ("escrow_item:<uuid>") exceed 32 characters.
ALTER TABLE pending_deliveries MODIFY item_type VARCHAR(80) NOT NULL;
