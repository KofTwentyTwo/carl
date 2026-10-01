/*
 * Copyright (C) 2026 KofTwentyTwo
 */
package com.kof22.carlai.domain;


import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;

import com.kof22.agentadmin.client.FamilyAccess;


/** Resolves only operator-provisioned verified issuer/subject mappings. */
public final class CarlFamilyAccess implements FamilyAccess
{
   private final CarlService service;

   /** Uses the same current membership store as Carl's domain capabilities. */
   public CarlFamilyAccess(CarlService service)
   {
      this.service = service;
   }



   @Override
   public Optional<Member> find(String issuer, String subject)
   {
      return service.transaction(c ->
      {
         var rows = CarlService.rows(c, "SELECT m.principal FROM carl_identity i JOIN carl_member m ON m.id=i.member_id WHERE i.issuer=? AND i.subject=? AND m.active", issuer, subject);
         if(rows.isEmpty())
         {
            return Optional.empty();
         }
         var member = CarlService.member(c, rows.getFirst().get("principal").toString());
         return Optional.of(new Member(Long.toString(member.id()), Long.toString(member.householdId()), member.principal(), Long.toString(member.permissionRevision())));
      });
   }



   @Override
   public Set<String> members(String household)
   {
      if(!household.matches("[1-9][0-9]{0,17}"))
      {
         return Set.of();
      }
      return service.transaction(c ->
      {
         var result = new LinkedHashSet<String>();
         CarlService.rows(c, "SELECT id FROM carl_member WHERE household_id=? AND active", Long.parseLong(household)).forEach(row -> result.add(row.get("id").toString()));
         return Set.copyOf(result);
      });
   }



   @Override
   public Set<String> tools(Member member)
   {
      return Set.of("carl_read_bills", "carl_read_finances", "carl_read_records", "carl_read_budget", "carl_read_preferences", "carl_read_availability");
   }
}
