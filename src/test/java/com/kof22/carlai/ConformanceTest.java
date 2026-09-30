/*
 * Copyright (C) 2026 KofTwentyTwo
 */
package com.kof22.carlai;


import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;

import com.kof22.agentcore.conformance.AbstractConformanceSuite;


/*******************************************************************************
 * Runs the shared runtime contract against this application's voice-only persona.
 ******************************************************************************/
class ConformanceTest extends AbstractConformanceSuite
{
   @Override
   protected String personaText()
   {
      try
      {
         return Files.readString(Path.of("prompts/PERSONA.md"));
      }
      catch(IOException failure)
      {
         throw new UncheckedIOException(failure);
      }
   }
}
