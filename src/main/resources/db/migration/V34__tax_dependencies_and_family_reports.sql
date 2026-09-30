-- Copyright (C) 2026 KofTwentyTwo
CREATE OR REPLACE VIEW carl_tax_property_view AS SELECT a.id,a.principal,a.title,a.evidence,a.revision,
 p.currency,p.ownership_share,p.acquisition_date,p.acquisition_basis,p.land_basis,p.building_basis,
 p.placed_in_service,p.financed,p.basis_evidence,p.tax_context_evidence
 FROM carl_rental_property_view a JOIN carl_property p ON p.record_id=a.id
 JOIN carl_member m ON m.principal=a.principal AND m.active
 JOIN carl_permission permission ON permission.member_id=m.id AND permission.domain='TAX' AND permission.details;
ALTER TABLE carl_client_workflow DROP CONSTRAINT carl_client_workflow_kind_check;
ALTER TABLE carl_client_workflow ADD CONSTRAINT carl_client_workflow_kind_check CHECK(kind IN ('report','draft','debt-comparison','purchase-assessment','offer-comparison','rental-report','tax-packet'));
