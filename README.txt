jSMTLIB is an open source implementation of SMT-LIB in Java, as a command-line tool, an Eclipse plugin, and an API

This is an de-opignionated version of jSMTLIB, which removes several unnessessary restrictions and provides
consistent and usable error reporting.

In more detail, the differences with respect to the standard version of jSMTLIB are:
- implements UNSAT cores
- allows using get_value() to query the generated models.
- implements declare_const()
- improved Error reporting: error messages originating from jSMTLIB are now clearly distinguishable from error messages originating from the solver.
- does NOT call Z3 with the SMTLIB2_COMPLIANT=true command-line argument, which disables all non-SMTLIB compliant functionality (like timeouts and negative integer constants)
- allows using timeouts (like supported for instance by Z3)
- allows using negative integer constants

planned (but not yet completed)
- using Java Exceptions for Error Reporting instead of SMTLIB-style error strings.


