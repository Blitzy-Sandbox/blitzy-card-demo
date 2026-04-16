//READCUST JOB 'Read Customer Data file',CLASS=A,MSGCLASS=0,
// NOTIFY=&SYUID
//* JOB: READCUST - Customer File Diagnostic Read Utility
//* Executes batch COBOL program CBCUS01C to sequentially
//* read and display all records from the Customer Master
//* VSAM KSDS file. Used for validation and debugging
//* after provisioning with CUSTFILE.jcl.
//* Related program source: app/cbl/CBCUS01C.cbl
//* Related dataset: AWS.M2.CARDDEMO.CUSTDATA.VSAM.KSDS
//* NOTE: This file lacks the standard Apache 2.0 license
//*   header present in other JCL members.
//* *******************************************************************
//* RUN THE PROGRAM THAT READS THE CUSTOMER MASTER VSAM FILE
//* *******************************************************************
//* STEP05: Execute CBCUS01C - reads CUSTDATA VSAM KSDS
//*   sequentially and writes record contents to SYSOUT
//STEP05 EXEC PGM=CBCUS01C
//* STEPLIB: CardDemo compiled load module library
//STEPLIB  DD DISP=SHR,
//         DSN=AWS.M2.CARDDEMO.LOADLIB
//* CUSTFILE: Customer Data VSAM KSDS (input, read-only)
//*   500-byte records, key=9 bytes at offset 0
//*   Layout: app/cpy/CVCUS01Y.cpy
//CUSTFILE DD DISP=SHR,
//         DSN=AWS.M2.CARDDEMO.CUSTDATA.VSAM.KSDS
//* SYSOUT: Captures program DISPLAY output for review
//SYSOUT   DD SYSOUT=*
//* SYSPRINT: System messages and runtime diagnostics
//SYSPRINT DD SYSOUT=*
//*
//* Ver: CardDemo_v1.0-15-g27d6c6f-68 Date: 2022-07-19 23:23:07 CDT
//*
