/* Copyright (C) 2026 KofTwentyTwo */
package com.kof22.agentadmin;


import com.auth0.jwk.Jwk;
import com.kof22.agentcore.security.RbacService;


/** Test-only bridge to the existing controlled loopback issuer constructor, without changing production APIs. */
public final class SyntheticDashboardScopeIdentity
{
   private SyntheticDashboardScopeIdentity()
   {
   }



   /** Uses the supplied synthetic signing key; native QQQ still reads the actual loopback JWKS endpoint. */
   public static BearerIdentity create(String issuer, RbacService rbac, Jwk key)
   {
      return new BearerIdentity(issuer, "carl-admin", "synthetic-client", rbac, ignored -> key);
   }
}
