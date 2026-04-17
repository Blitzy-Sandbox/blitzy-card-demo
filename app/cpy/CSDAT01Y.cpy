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
      * Utility Copybook: Date/time working storage fields
      * Provides multiple views of current date and time
      * for screen display and transaction timestamping.
      * Populated at runtime via FUNCTION CURRENT-DATE
      * or EXEC CICS ASKTIME/FORMATTIME.
      *
      * Consumed by nearly all online CICS programs via
      * COPY CSDAT01Y: COSGN00C, COMEN01C, COADM01C,
      * COACTVWC, COACTUPC, COCRDLIC, COCRDSLC,
      * COCRDUPC, COTRN00C, COTRN01C, COTRN02C,
      * COBIL00C, CORPT00C, COUSR00C, COUSR01C,
      * COUSR02C, COUSR03C
      *----------------------------------------------------------------*
       01 WS-DATE-TIME.
      * Raw date/time components from system functions.
      * Contains both component and numeric views.
         05 WS-CURDATE-DATA.
      * Date in CCYYMMDD format - component view allows
      * individual YEAR/MONTH/DAY access
           10  WS-CURDATE.
             15  WS-CURDATE-YEAR         PIC 9(04).
             15  WS-CURDATE-MONTH        PIC 9(02).
             15  WS-CURDATE-DAY          PIC 9(02).
      * Numeric REDEFINES for date arithmetic (PIC 9(8))
           10 WS-CURDATE-N REDEFINES WS-CURDATE PIC 9(08).
      * Time in HHMMSSMS format - component view allows
      * individual HOURS/MINUTE/SECOND/MILSEC access
           10  WS-CURTIME.
             15  WS-CURTIME-HOURS        PIC 9(02).
             15  WS-CURTIME-MINUTE       PIC 9(02).
             15  WS-CURTIME-SECOND       PIC 9(02).
             15  WS-CURTIME-MILSEC       PIC 9(02).
      * Numeric REDEFINES for time calculations (PIC 9(8))
           10 WS-CURTIME-N REDEFINES WS-CURTIME PIC 9(08).
      * Formatted display date with "/" separators
      * for BMS screen headers (MM/DD/YY format)
         05 WS-CURDATE-MM-DD-YY.
           10  WS-CURDATE-MM             PIC 9(02).
           10  FILLER                    PIC X(01) VALUE '/'.
           10  WS-CURDATE-DD             PIC 9(02).
           10  FILLER                    PIC X(01) VALUE '/'.
           10  WS-CURDATE-YY             PIC 9(02).
      * Formatted display time with ":" separators
      * for BMS screen headers (HH:MM:SS format)
         05 WS-CURTIME-HH-MM-SS.
           10  WS-CURTIME-HH             PIC 9(02).
           10  FILLER                    PIC X(01) VALUE ':'.
           10  WS-CURTIME-MM             PIC 9(02).
           10  FILLER                    PIC X(01) VALUE ':'.
           10  WS-CURTIME-SS             PIC 9(02).
      * ISO 8601-like timestamp for transaction logging
      * 26 chars: YYYY-MM-DD HH:MM:SS.MMMMMM
         05 WS-TIMESTAMP.
           10  WS-TIMESTAMP-DT-YYYY      PIC 9(04).
           10  FILLER                    PIC X(01) VALUE '-'.
           10  WS-TIMESTAMP-DT-MM        PIC 9(02).
           10  FILLER                    PIC X(01) VALUE '-'.
           10  WS-TIMESTAMP-DT-DD        PIC 9(02).
           10  FILLER                    PIC X(01) VALUE ' '.
           10  WS-TIMESTAMP-TM-HH        PIC 9(02).
           10  FILLER                    PIC X(01) VALUE ':'.
           10  WS-TIMESTAMP-TM-MM        PIC 9(02).
           10  FILLER                    PIC X(01) VALUE ':'.
           10  WS-TIMESTAMP-TM-SS        PIC 9(02).
           10  FILLER                    PIC X(01) VALUE '.'.
           10  WS-TIMESTAMP-TM-MS6       PIC 9(06).
      *
      * Ver: CardDemo_v1.0-15-g27d6c6f-68 Date: 2022-07-19 23:15:58 CDT
      *
