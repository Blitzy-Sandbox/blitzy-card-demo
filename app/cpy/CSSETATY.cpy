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
      *    Set (TESTVAR1) to red if in error and * if blankACSHLIM
      *----------------------------------------------------------------*
      * CSSETATY - BMS Field Attribute Setting Template
      *----------------------------------------------------------------*
      * This copybook is a reusable template included via COBOL
      * COPY ... REPLACING syntax. Each inclusion substitutes
      * three pseudo-text tokens to apply field-level validation
      * highlighting on a specific BMS screen field:
      *
      *   (TESTVAR1) - Validation flag field name suffix.
      *     Checked as FLG-(TESTVAR1)-NOT-OK and
      *     FLG-(TESTVAR1)-BLANK conditions.
      *   (SCRNVAR2) - BMS symbolic map field name prefix.
      *     Addresses the color attribute (C suffix) and
      *     output value (O suffix) of the field.
      *   (MAPNAME3) - BMS mapset name. Qualifies the
      *     symbolic map output structure (O suffix).
      *
      * Usage example from COACTUPC.cbl:
      *   COPY CSSETATY REPLACING
      *     ==(TESTVAR1)== BY ==ACCT-STATUS==
      *     ==(SCRNVAR2)== BY ==ACSTTUS==
      *     ==(MAPNAME3)== BY ==CACTUPA== .
      *
      * Logic flow:
      *  1. Checks if the field validation flag indicates
      *     an error (FLG-xxx-NOT-OK) or a blank required
      *     field (FLG-xxx-BLANK), AND the program is in
      *     re-entry mode (CDEMO-PGM-REENTER is true).
      *     CDEMO-PGM-REENTER is an 88-level condition
      *     defined in COCOM01Y.cpy COMMAREA (value 1).
      *  2. If the condition is met, sets the BMS field
      *     color attribute to DFHRED (red) via the CICS
      *     BMS attribute constant from the DFHBMSCA
      *     system copybook.
      *  3. If the field is specifically blank, also moves
      *     an asterisk '*' into the output field value
      *     as a visual required-field indicator.
      *
      * Cross-references:
      *   COCOM01Y.cpy  - CDEMO-PGM-REENTER 88-level
      *     condition on CDEMO-PGM-CONTEXT (value 1)
      *   DFHBMSCA      - CICS system copybook providing
      *     the DFHRED color attribute constant
      *   COACTUPC.cbl  - Primary consumer with 39
      *     instances for account update field validation
      *----------------------------------------------------------------*
           IF (FLG-(TESTVAR1)-NOT-OK                                 
           OR  FLG-(TESTVAR1)-BLANK)                                    
           AND CDEMO-PGM-REENTER                                        
               MOVE DFHRED             TO 
                    (SCRNVAR2)C OF (MAPNAME3)O
               IF  FLG-(TESTVAR1)-BLANK                                 
                   MOVE '*'            TO 
                    (SCRNVAR2)O OF (MAPNAME3)O
               END-IF                                                   
           END-IF 
      *
      * Ver: CardDemo_v1.0-15-g27d6c6f-68 Date: 2022-07-19 23:15:58 CDT
      *
