#include "expat.h"
#include <libxml/parser.h>
#include <cstring>
#include <cstdlib>

struct XML_ParserStruct {
    xmlParserCtxtPtr ctxt;
    void* userData;
    XML_StartElementHandler startH;
    XML_EndElementHandler endH;
    XML_CharacterDataHandler charH;
    XML_Error lastError;
};

// libxml2 SAX callbacks
static void libxmlStartElement(void* ctx, const xmlChar* name, const xmlChar** atts) {
    auto* p = (XML_ParserStruct*)ctx;
    if (p->startH) {
        // Expat expects attributes as a flattened array of name, value, ...
        // libxml2 gives them as name, value, name, value, ... ending with NULL
        // We can pass them directly if they match.
        p->startH(p->userData, (const char*)name, (const char**)atts);
    }
}

static void libxmlEndElement(void* ctx, const xmlChar* name) {
    auto* p = (XML_ParserStruct*)ctx;
    if (p->endH) {
        p->endH(p->userData, (const char*)name);
    }
}

static void libxmlCharacters(void* ctx, const xmlChar* ch, int len) {
    auto* p = (XML_ParserStruct*)ctx;
    if (p->charH) {
        p->charH(p->userData, (const char*)ch, len);
    }
}

XML_Parser XML_ParserCreate(const char* encoding) {
    auto* p = (XML_ParserStruct*)malloc(sizeof(XML_ParserStruct));
    if (!p) return nullptr;
    p->userData = nullptr;
    p->startH = nullptr;
    p->endH = nullptr;
    p->charH = nullptr;
    p->lastError = XML_ERROR_NONE;
    
    static xmlSAXHandler handler;
    memset(&handler, 0, sizeof(handler));
    handler.initialized = XML_SAX2_MAGIC;
    handler.startElement = libxmlStartElement;
    handler.endElement = libxmlEndElement;
    handler.characters = libxmlCharacters;
    
    p->ctxt = xmlCreatePushParserCtxt(&handler, p, nullptr, 0, nullptr);
    if (!p->ctxt) {
        free(p);
        return nullptr;
    }
    return (XML_Parser)p;
}

void XML_SetUserData(XML_Parser p, void* userData) {
    if (p) ((XML_ParserStruct*)p)->userData = userData;
}

void XML_SetElementHandler(XML_Parser p, XML_StartElementHandler start, XML_EndElementHandler end) {
    if (p) {
        auto* ps = (XML_ParserStruct*)p;
        ps->startH = start;
        ps->endH = end;
    }
}

void XML_SetCharacterDataHandler(XML_Parser p, XML_CharacterDataHandler handler) {
    if (p) ((XML_ParserStruct*)p)->charH = handler;
}

XML_Status XML_Parse(XML_Parser p, const char* s, int len, int isFinal) {
    if (!p) return XML_STATUS_ERROR;
    auto* ps = (XML_ParserStruct*)p;
    int res = xmlParseChunk(ps->ctxt, s, len, isFinal);
    if (res != 0) {
        ps->lastError = XML_ERROR_SYNTAX;
        return XML_STATUS_ERROR;
    }
    return XML_STATUS_OK;
}

const char* XML_ErrorString(XML_Error code) {
    return "XML Parse Error (libxml2 shim)";
}

XML_Error XML_GetErrorCode(XML_Parser p) {
    return p ? ((XML_ParserStruct*)p)->lastError : XML_ERROR_SYNTAX;
}

void XML_ParserFree(XML_Parser p) {
    if (p) {
        auto* ps = (XML_ParserStruct*)p;
        if (ps->ctxt) xmlFreeParserCtxt(ps->ctxt);
        free(ps);
    }
}
