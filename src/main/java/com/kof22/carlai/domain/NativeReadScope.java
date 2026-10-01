/* Copyright (C) 2026 KofTwentyTwo */
package com.kof22.carlai.domain;


/** Native request-owned scope is populated only by a caller-aware capability after transport verification. */
public final class NativeReadScope
{
   private static final ThreadLocal<State> CURRENT = new ThreadLocal<>();

   private NativeReadScope()
   {
   }

   /** One request captures its first verified full membership snapshot. */
   public static final class State
   {
      private CarlService.Member member;

      /** Reads a snapshot after native execution; never depends on a cleared QContext. */
      public CarlService.Member member()
      {
         return member;
      }
   }

   /** A native route provider owns this state; no request parameter selects identity. */
   public static State begin()
   {
      State state = new State();
      CURRENT.set(state);
      return state;
   }



   /** Called with an independently verified current member after ordinary domain authentication. */
   public static void check(CarlService.Member member)
   {
      State state = CURRENT.get();
      if(state != null)
      {
         if(state.member == null)
         {
            state.member = member;
         }
         else if(!state.member.equals(member))
         {
            throw new SecurityException("Carl native access changed during this request");
         }
      }
   }



   /** Releases only the request-owned holder. */
   public static void end()
   {
      CURRENT.remove();
   }
}
