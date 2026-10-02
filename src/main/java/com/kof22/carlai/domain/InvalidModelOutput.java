/* Copyright (C) 2026 KofTwentyTwo */
package com.kof22.carlai.domain;


/** The model's structured output could not be used; nothing derived from it was executed. */
final class InvalidModelOutput extends IllegalArgumentException
{
   InvalidModelOutput(String message)
   {
      super(message);
   }
}
