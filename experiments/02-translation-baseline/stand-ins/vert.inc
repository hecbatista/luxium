#define attribute in
#define gl_Vertex ms_Vertex
#define gl_Color ms_Color
#define gl_MultiTexCoord0 ms_MultiTexCoord0
#define gl_MultiTexCoord1 ms_MultiTexCoord1
#define gl_Normal ms_Normal
in vec4 ms_Vertex; in vec4 ms_Color; in vec4 ms_MultiTexCoord0; in vec4 ms_MultiTexCoord1; in vec3 ms_Normal;
#define ftransform() (ms_ModelViewProjectionMatrix * ms_Vertex)
