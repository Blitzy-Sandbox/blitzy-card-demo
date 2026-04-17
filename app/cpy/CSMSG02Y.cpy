000700*****************************************************************
000800* CABENDD.CPY                                                   *
000900*---------------------------------------------------------------*
001000* Work areas for abend routine                                  *
      ******************************************************************
      * Copyright Amazon.com, Inc. or its affiliates.                   
      * All Rights Reserved.                                            
      *                                                                 
      * Licensed under the Apache License, Version 2.0 (the "License"). 
      * You may not use this file except in compliance with the License.
      * You may obtain a copy of the License at                         
      *                                                                 
      *    http://www.apache.org/licenses/LICENSE-2.0                   
      *                                                                 
      * Unless required by applicable law or agreed to in writing,      
      * software distributed under the License is distributed on an     
      * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND,    
      * either express or implied. See the License for the specific     
      * language governing permissions and limitations under the License
      ****************************************************************** 
      *----------------------------------------------------------------*
      * Screen/UI Copybook: CICS abend diagnostic work area
      * Original copybook name: CABENDD.CPY (per header)
      *
      * Captures structured error context when a CICS program
      * encounters an abend condition. Provides a 134-byte work
      * area with fields for the abend code, culprit program,
      * human-readable reason, and a formatted message.
      *
      * Used by online programs to format and display error
      * information on BMS screens during exception handling.
      *
      * Consuming programs:
      *   - COACTUPC.cbl (Account update)
      *   - COACTVWC.cbl (Account view)
      *   - COCRDLIC.cbl (Card list browse)
      *   - COCRDSLC.cbl (Card detail view)
      *   - COCRDUPC.cbl (Card update)
      *----------------------------------------------------------------*
001200 01  ABEND-DATA.
      * ABEND-CODE: 4-char CICS abend code (e.g., AEI0, ASRA)
      *   Populated during error handling before displaying
      *   the error screen. Initialized to SPACES.
001300   05  ABEND-CODE                            PIC X(4)
001400       VALUE SPACES.
      * ABEND-CULPRIT: 8-char program name that caused the abend
      *   Identifies the failing program for diagnostic display.
      *   Initialized to SPACES.
001500   05  ABEND-CULPRIT                         PIC X(8)
001600       VALUE SPACES.
      * ABEND-REASON: Human-readable error cause description
      *   50-char text providing context for the abend.
      *   Initialized to SPACES.
001700   05  ABEND-REASON                          PIC X(50)
001800       VALUE SPACES.
      * ABEND-MSG: Formatted diagnostic message for display
      *   72 chars matches BMS screen line width for direct
      *   output to error screens. Initialized to SPACES.
001900   05  ABEND-MSG                             PIC X(72)
002000       VALUE SPACES.



      *
      * Ver: CardDemo_v1.0-15-g27d6c6f-68 Date: 2022-07-19 23:15:58 CDT
      *
