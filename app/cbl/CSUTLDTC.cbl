      ******************************************************************        
      *****       CALL TO CEEDAYS                                *******        
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
      * CSUTLDTC - Date Validation Subprogram
      *----------------------------------------------------------------*
      * Wraps the IBM Language Environment (LE) CEEDAYS callable
      * service to validate date strings against a format mask
      * and return a Lilian date value (days since Oct 15, 1582).
      *
      * Called via EXEC CICS LINK PROGRAM('CSUTLDTC') from
      * online programs or via COBOL CALL 'CSUTLDTC' from
      * batch programs. Programs that COPY CSUTLDPY.cpy invoke
      * this subprogram in the EDIT-DATE-LE paragraph as a
      * final validation step after manual date checks.
      *
      * Input:  LS-DATE (date string, up to 10 characters)
      *         LS-DATE-FORMAT (format mask, e.g. 'YYYYMMDD')
      * Output: LS-RESULT (80-byte diagnostic message with
      *         severity, msg code, result, date, and mask)
      *         RETURN-CODE (numeric severity: 0=valid)
      *
      * No ENVIRONMENT DIVISION -- pure computational subprogram
      * with no file I/O or CICS service dependencies.
      *
      * Cross-references:
      *   Caller copybook: CSUTLDPY.cpy (EDIT-DATE-LE para)
      *   Caller storage:  CSUTLDWY.cpy (date-edit fields)
      *   Called by: COCRDUPC, COTRN02C, and other programs
      *              requiring date field validation
      *----------------------------------------------------------------*
       IDENTIFICATION DIVISION.                                                 
       PROGRAM-ID. CSUTLDTC.                                                    
       DATA DIVISION.                                                           
       WORKING-STORAGE SECTION.                                                 
                                                                                
      ****  Date passed to CEEDAYS API                                          
      * LE varying-length string (Vstring) for the input date.
      * S9(4) BINARY length prefix + OCCURS DEPENDING ON text.
      * CEEDAYS requires this Vstring structure for input.
         01 WS-DATE-TO-TEST.                                                    
              02  Vstring-length      PIC S9(4) BINARY.                         
              02  Vstring-text.                                                 
                  03  Vstring-char    PIC X                                     
                              OCCURS 0 TO 256 TIMES                             
                              DEPENDING ON Vstring-length                       
                                 of WS-DATE-TO-TEST.                            
      ****  DATE FORMAT PASSED TO CEEDAYS API                                   
      * LE Vstring for the date format mask. Contains the
      * pattern telling CEEDAYS how to parse the input date
      * (e.g. 'YYYYMMDD' = 4-digit year, 2-digit month/day).
         01 WS-DATE-FORMAT.                                                     
              02  Vstring-length      PIC S9(4) BINARY.                         
              02  Vstring-text.                                                 
                  03  Vstring-char    PIC X                                     
                              OCCURS 0 TO 256 TIMES                             
                              DEPENDING ON Vstring-length                       
                                 of WS-DATE-FORMAT.                             
      ****  OUTPUT from CEEDAYS - LILLIAN DATE FORMAT                           
      * Lilian date: days since October 15, 1582 (start of
      * Gregorian calendar). Non-zero confirms valid date.
         01 OUTPUT-LILLIAN    PIC S9(9) USAGE IS BINARY.                        
      * Composite diagnostic message returned to caller via
      * LS-RESULT. Contains severity, message code, result
      * text, tested date, and format mask for diagnostics.
         01 WS-MESSAGE.                                                         
      * LE severity code (alphanumeric view): 0=success,
      * 1=warning, 2=error, 3=severe, 4=critical
              02 WS-SEVERITY  PIC X(04).                                        
      * Numeric REDEFINES for conditional severity testing
              02 WS-SEVERITY-N REDEFINES WS-SEVERITY PIC 9(4).                  
              02 FILLER       PIC X(11) VALUE 'Mesg Code:'.                     
      * LE message number identifying the specific error
              02 WS-MSG-NO    PIC X(04).                                        
      * Numeric REDEFINES for message number operations
              02 WS-MSG-NO-N  REDEFINES WS-MSG-NO PIC 9(4).                     
              02 FILLER       PIC X(01) VALUE SPACE.                            
      * Human-readable result text (e.g. 'Date is valid')
              02 WS-RESULT    PIC X(15).                                        
              02 FILLER       PIC X(01) VALUE SPACE.                            
              02 FILLER       PIC X(09) VALUE 'TstDate:'.                       
      * Copy of tested date value for diagnostic display
              02 WS-DATE      PIC X(10) VALUE SPACES.                           
              02 FILLER       PIC X(01) VALUE SPACE.                            
              02 FILLER       PIC X(10) VALUE 'Mask used:'.                     
      * Copy of format mask used for diagnostic display
              02 WS-DATE-FMT  PIC X(10).                                        
              02 FILLER       PIC X(01) VALUE SPACE.                            
              02 FILLER       PIC X(03) VALUE SPACES.                           
                                                                                
      * CEEDAYS API FEEDBACK CODE                                               
      * 16-byte IBM LE condition token structure. CEEDAYS
      * populates this with success/failure information.
      * Contains condition ID (severity + msg number),
      * severity control byte, facility ID, and ISI field.
          01 FEEDBACK-CODE.                                                     
           02  FEEDBACK-TOKEN-VALUE. 
      * 88-level conditions map hex feedback token values
      * to named CEEDAYS error conditions. FC-INVALID-DATE
      * (all zeros) indicates success; others map to LE
      * error message numbers (2507-2521).
             88  FC-INVALID-DATE       VALUE X'0000000000000000'.
             88  FC-INSUFFICIENT-DATA  VALUE X'000309CB59C3C5C5'.
             88  FC-BAD-DATE-VALUE     VALUE X'000309CC59C3C5C5'.
             88  FC-INVALID-ERA        VALUE X'000309CD59C3C5C5'.
             88  FC-UNSUPP-RANGE       VALUE X'000309D159C3C5C5'.
             88  FC-INVALID-MONTH      VALUE X'000309D559C3C5C5'.
             88  FC-BAD-PIC-STRING     VALUE X'000309D659C3C5C5'.
             88  FC-NON-NUMERIC-DATA   VALUE X'000309D859C3C5C5'.
             88  FC-YEAR-IN-ERA-ZERO   VALUE X'000309D959C3C5C5'.
      * Condition ID view 1: severity + message number
               03  CASE-1-CONDITION-ID.                                         
                   04  SEVERITY        PIC S9(4) BINARY.                        
                   04  MSG-NO          PIC S9(4) BINARY.                        
      * Condition ID view 2: class + cause (alternate view)
               03  CASE-2-CONDITION-ID                                          
                         REDEFINES CASE-1-CONDITION-ID.                         
                   04  CLASS-CODE      PIC S9(4) BINARY.                        
                   04  CAUSE-CODE      PIC S9(4) BINARY.                        
      * Severity control byte and LE facility identifier
               03  CASE-SEV-CTL    PIC X.                                       
               03  FACILITY-ID     PIC XXX.                                     
      * Installation-specific information (LE runtime)
           02  I-S-INFO        PIC S9(9) BINARY.                                
                                                                                
                                                                                
      *----------------------------------------------------------------*
      * Parameters received from calling program via CALL
      *----------------------------------------------------------------*
       LINKAGE SECTION.                                                         
      * Input: date string to validate (e.g. '20241231')
          01 LS-DATE         PIC X(10).                                         
      * Input: format mask for parsing (e.g. 'YYYYMMDD')
          01 LS-DATE-FORMAT  PIC X(10).                                         
      * Output: 80-byte diagnostic message (WS-MESSAGE)
          01 LS-RESULT       PIC X(80).                                         
                                                                                
      *----------------------------------------------------------------*
      * Entry point: receives date, format mask, and result
      * area from caller. Initializes work areas, performs
      * A000-MAIN for CEEDAYS validation, then returns the
      * diagnostic message and severity code to caller.
      *----------------------------------------------------------------*
       PROCEDURE DIVISION USING LS-DATE, LS-DATE-FORMAT, LS-RESULT.             
           
      * Clears diagnostic message area before processing
           INITIALIZE WS-MESSAGE
           MOVE SPACES TO WS-DATE
                                                                        
      * Invokes core validation via CEEDAYS API
           PERFORM A000-MAIN                                                    
              THRU A000-MAIN-EXIT                                               

      *    DISPLAY WS-MESSAGE                                                   
      * Copies diagnostic message to caller result area
           MOVE WS-MESSAGE                 TO LS-RESULT 
      * Sets RETURN-CODE to numeric severity for caller
           MOVE WS-SEVERITY-N              TO RETURN-CODE          
                                                                                
      * Returns control to calling program
           EXIT PROGRAM                                                         
      *    GOBACK                                                               
           .                                                                    
      *----------------------------------------------------------------*
      * A000-MAIN: Core validation logic
      * Populates LE Vstring structures from linkage params,
      * calls CEEDAYS for Gregorian-to-Lilian conversion,
      * extracts severity and message from feedback token,
      * and maps the token to a human-readable result.
      *----------------------------------------------------------------*
       A000-MAIN.                                                               
                                                                                
      * Populates input date Vstring: sets length prefix
      * and copies date into variable-length text area.
      * Also saves date to WS-DATE for diagnostic display.
           MOVE LENGTH OF LS-DATE                                               
                        TO VSTRING-LENGTH  OF WS-DATE-TO-TEST                   
           MOVE LS-DATE TO VSTRING-TEXT    OF WS-DATE-TO-TEST
                           WS-DATE                  
      * Populates format mask Vstring and saves mask copy
      * to WS-DATE-FMT for diagnostic display.
           MOVE LENGTH OF LS-DATE-FORMAT                                        
                         TO VSTRING-LENGTH OF WS-DATE-FORMAT                    
           MOVE LS-DATE-FORMAT                                                  
                         TO VSTRING-TEXT   OF WS-DATE-FORMAT   
                            WS-DATE-FMT  
      * Initializes Lilian date output to zero before call
           MOVE 0        TO OUTPUT-LILLIAN                              
                                                                        
      * Calls IBM LE CEEDAYS callable service to convert
      * the Gregorian date string to a Lilian day number.
      * CEEDAYS populates OUTPUT-LILLIAN on success and
      * sets FEEDBACK-CODE with the result status.
           CALL "CEEDAYS" USING                                                 
                  WS-DATE-TO-TEST,                                              
                  WS-DATE-FORMAT,                                               
                  OUTPUT-LILLIAN,                                               
                  FEEDBACK-CODE                                                 
                                                                                
      * Extracts severity and message number from the LE
      * feedback token into diagnostic message fields
           MOVE WS-DATE-TO-TEST            TO WS-DATE                           
           MOVE SEVERITY OF FEEDBACK-CODE  TO WS-SEVERITY-N                     
           MOVE MSG-NO OF FEEDBACK-CODE    TO WS-MSG-NO-N                       
                                                                 
      * Maps FEEDBACK-CODE condition token to a descriptive
      * result string. Each 88-level condition corresponds
      * to a specific CEEDAYS error or success state.
      *    WS-RESULT IS 15 CHARACTERS                                           
      *                123456789012345'                                         
           EVALUATE TRUE                                                        
      * FC-INVALID-DATE (all zeros) means date IS valid
      * despite the misleading condition name
              WHEN FC-INVALID-DATE                                   
                 MOVE 'Date is valid'      TO WS-RESULT              
              WHEN FC-INSUFFICIENT-DATA                              
                 MOVE 'Insufficient'       TO WS-RESULT              
              WHEN FC-BAD-DATE-VALUE                                 
                 MOVE 'Datevalue error'    TO WS-RESULT              
              WHEN FC-INVALID-ERA                                    
                 MOVE 'Invalid Era    '    TO WS-RESULT              
              WHEN FC-UNSUPP-RANGE                                   
                 MOVE 'Unsupp. Range  '    TO WS-RESULT              
              WHEN FC-INVALID-MONTH                                  
                 MOVE 'Invalid month  '    TO WS-RESULT              
              WHEN FC-BAD-PIC-STRING                                 
                 MOVE 'Bad Pic String '    TO WS-RESULT              
              WHEN FC-NON-NUMERIC-DATA                               
                 MOVE 'Nonnumeric data'    TO WS-RESULT              
              WHEN FC-YEAR-IN-ERA-ZERO                               
                 MOVE 'YearInEra is 0 '    TO WS-RESULT              
      * Catches any unrecognized CEEDAYS feedback token
              WHEN OTHER                                             
                 MOVE 'Date is invalid'    TO WS-RESULT 
           END-EVALUATE                                                         
                                                                                
           .                                                                    
      * Exit point for A000-MAIN PERFORM THRU target
       A000-MAIN-EXIT.                                                          
           EXIT                                                                 
           .                                                                    
      *
      * Ver: CardDemo_v1.0-15-g27d6c6f-68 Date: 2022-07-19 23:12:35 CDT
      *
