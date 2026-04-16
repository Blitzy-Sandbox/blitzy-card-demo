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
      *
      * Screen/UI Copybook: Common user-facing message definitions
      * Provides pre-formatted 50-character message strings
      * displayed in the message area of CardDemo BMS screens.
      *
      * All messages are PIC X(50) to match the standard BMS
      * message field width used across all online screens.
      *
      * Consumed by nearly all online CICS programs via
      * COPY CSMSG01Y:
      *   COSGN00C, COMEN01C, COADM01C, COACTVWC, COACTUPC,
      *   COCRDLIC, COCRDSLC, COCRDUPC, COTRN00C, COTRN01C,
      *   COTRN02C, COBIL00C, CORPT00C, COUSR00C, COUSR01C,
      *   COUSR02C, COUSR03C
      *
      * Cross-references:
      *   BMS maps - Message areas in all app/bms/*.bms screens
      *   See also CSMSG02Y.cpy for abend message work area
      *
       01 CCDA-COMMON-MESSAGES.
      * Graceful exit message displayed on application sign-off
         05 CCDA-MSG-THANK-YOU         PIC X(50) VALUE
              'Thank you for using CardDemo application...      '.
      * Invalid key message shown when user presses unrecognized
      * PF key or AID; directs user to function key legend
         05 CCDA-MSG-INVALID-KEY       PIC X(50) VALUE
              'Invalid key pressed. Please see below...         '.
      *
      * Ver: CardDemo_v1.0-15-g27d6c6f-68 Date: 2022-07-19 23:15:58 CDT
      *
