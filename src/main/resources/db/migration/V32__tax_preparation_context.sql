-- Copyright (C) 2026 KofTwentyTwo
ALTER TABLE carl_property ADD COLUMN placed_in_service date;
ALTER TABLE carl_property ADD COLUMN financed boolean;
ALTER TABLE carl_property ADD COLUMN tax_context_evidence text;
CREATE VIEW carl_tax_property_view AS SELECT a.id,a.principal,a.title,a.evidence,a.revision,
 p.currency,p.ownership_share,p.acquisition_date,p.acquisition_basis,p.land_basis,p.building_basis,
 p.placed_in_service,p.financed,p.basis_evidence,p.tax_context_evidence
 FROM carl_access a JOIN carl_property p ON p.record_id=a.id
 JOIN carl_member m ON m.principal=a.principal AND m.active
 JOIN carl_permission permission ON permission.member_id=m.id AND permission.domain='TAX' AND permission.details
 WHERE a.details;
CREATE OR REPLACE VIEW carl_tax_view AS SELECT a.id,a.principal,a.title,a.evidence,t.tax_year,t.jurisdiction,t.document_kind,t.classification_state,t.treatment_evidence,a.revision,t.property_id
 FROM carl_access a JOIN carl_tax_document t ON t.record_id=a.id WHERE a.details
 AND (t.property_id IS NULL OR EXISTS(SELECT 1 FROM carl_tax_property_view property WHERE property.id=t.property_id AND property.principal=a.principal));
