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
      * Screen/UI Copybook: Application title and banner text
      * fields. Provides standardized 40-character header lines
      * displayed at the top of every CardDemo BMS screen via
      * SEND MAP operations.
      *
      * All fields are PIC X(40) to match BMS screen field
      * widths defined in the corresponding DFHMDF macros.
      *
      * Consuming programs: Nearly all online CICS programs
      *   COSGN00C, COMEN01C, COADM01C, COACTVWC, COACTUPC,
      *   COCRDLIC, COCRDSLC, COCRDUPC, COTRN00C, COTRN01C,
      *   COTRN02C, COBIL00C, CORPT00C, COUSR00C-COUSR03C
      *
      * Cross-references:
      *   BMS maps: All app/bms/*.bms display these titles
      *   Programs: All 18 online CICS programs via COPY COTTL01Y
      *----------------------------------------------------------------*
       01 CCDA-SCREEN-TITLE.
      * First banner line - "AWS Mainframe Modernization"
      * centered within a 40-character field
         05 CCDA-TITLE01    PIC X(40) VALUE
            '      AWS Mainframe Modernization       '.
      * Second banner line - displays "CardDemo"
      * Original text was "Credit Card Demo Application
      * (CCDA)" (see commented-out VALUE below)
         05 CCDA-TITLE02    PIC X(40) VALUE
      *     '  Credit Card Demo Application (CCDA)   '.
            '              CardDemo                  '.
      * Sign-off message shown when user exits the application
         05 CCDA-THANK-YOU  PIC X(40) VALUE
            'Thank you for using CCDA application... '.
      *
      * Ver: CardDemo_v1.0-15-g27d6c6f-68 Date: 2022-07-19 23:15:58 CDT
      *
