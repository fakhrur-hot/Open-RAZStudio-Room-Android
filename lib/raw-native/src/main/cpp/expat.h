#pragma once
#include <libxml/parser.h>

#ifdef __cplusplus
extern "C" {
#endif

typedef void* XML_Parser;
typedef char XML_Char;

typedef void (*XML_StartElementHandler)(void *userData, const char *name, const char **atts);
typedef void (*XML_EndElementHandler)(void *userData, const char *name);
typedef void (*XML_CharacterDataHandler)(void *userData, const char *s, int len);

enum XML_Status { 
    XML_STATUS_ERROR = 0, 
    XML_STATUS_OK = 1,
    XML_STATUS_SUSPENDED = 2
};

enum XML_Error { 
    XML_ERROR_NONE = 0, 
    XML_ERROR_NO_MEMORY,
    XML_ERROR_SYNTAX,
    XML_ERROR_NO_ELEMENTS,
    XML_ERROR_INVALID_TOKEN,
    XML_ERROR_UNCLOSED_TOKEN,
    XML_ERROR_PARTIAL_CHAR,
    XML_ERROR_TAG_MISMATCH,
    XML_ERROR_DUPLICATE_ATTRIBUTE,
    XML_ERROR_JUNK_AFTER_DOC_ELEMENT,
    XML_ERROR_PARAM_ENTITY_REF,
    XML_ERROR_UNDEFINED_ENTITY,
    XML_ERROR_RECURSIVE_ENTITY_REF,
    XML_ERROR_ASYNC_ENTITY,
    XML_ERROR_BAD_CHAR_REF,
    XML_ERROR_BINARY_ENTITY_REF,
    XML_ERROR_ATTRIBUTE_EXTERNAL_ENTITY_REF,
    XML_ERROR_MISPLACED_XML_PI,
    XML_ERROR_UNKNOWN_ENCODING,
    XML_ERROR_INCORRECT_ENCODING,
    XML_ERROR_UNCLOSED_CDATA_SECTION,
    XML_ERROR_EXTERNAL_ENTITY_HANDLING,
    XML_ERROR_NOT_STANDALONE,
    XML_ERROR_UNEXPECTED_STATE,
    XML_ERROR_ENTITY_DECLARED_IN_PE,
    XML_ERROR_FEATURE_REQUIRES_XML_DTD,
    XML_ERROR_CANT_CHANGE_FEATURE_ONCE_PARSING,
    XML_ERROR_UNBOUND_PREFIX,
    XML_ERROR_UNDECLARING_PREFIX,
    XML_ERROR_INCOMPLETE_PE,
    XML_ERROR_XML_DECL,
    XML_ERROR_TEXT_DECL,
    XML_ERROR_PUBLICID,
    XML_ERROR_SUSPENDED,
    XML_ERROR_NOT_SUSPENDED,
    XML_ERROR_ABORTED,
    XML_ERROR_FINISHED,
    XML_ERROR_SUSPEND_PE,
    XML_ERROR_RESERVED_PREFIX_XML,
    XML_ERROR_RESERVED_PREFIX_XMLNS,
    XML_ERROR_RESERVED_NAMESPACE_URI,
    XML_ERROR_INVALID_ARGUMENT,
    XML_ERROR_NO_BUFFER,
    XML_ERROR_AMPLIFICATION_LIMIT_BREACH,
    XML_ERROR_NOT_STARTED
};

#define XML_L(x) x
#define XMLCALL
#define XMLPARSEAPI(x) x

XML_Parser XML_ParserCreate(const char* encoding);
void XML_SetUserData(XML_Parser p, void* userData);
void XML_SetElementHandler(XML_Parser p, XML_StartElementHandler start, XML_EndElementHandler end);
void XML_SetCharacterDataHandler(XML_Parser p, XML_CharacterDataHandler handler);
XML_Status XML_Parse(XML_Parser p, const char* s, int len, int isFinal);
const char* XML_ErrorString(XML_Error code);
XML_Error XML_GetErrorCode(XML_Parser p);
void XML_ParserFree(XML_Parser p);

#ifdef __cplusplus
}
#endif
