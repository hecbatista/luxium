// pipecheck: builds real Metal render pipelines from vertex+fragment MSL pairs, outside the game,
// and reports what the driver says each pair reads and writes.
//
//   pipecheck <list.tsv>        each line: name <TAB> vertex.metal <TAB> fragment.metal
//
// For each pair: compile both stages with default options (Metallum compiles with
// newLibraryWithSource(source, nil)), build an MTLRenderPipelineState with
//   - a vertex descriptor made from the vertex function's own stage_in attributes (one buffer, packed),
//   - one colour attachment per [[color(n)]] the fragment source writes (RGBA16Float for float outputs,
//     RGBA32Uint / RGBA32Sint for uint / int outputs) and a Depth32Float depth attachment,
// and print one JSON line: ok or the error, the active vertex inputs, the colour outputs, and the
// buffers and textures each stage uses (MTLPipelineOptionBindingInfo reflection).
// Pixel formats here are stand-ins: this checks interfaces (does the pair link, what does it use),
// not the pack's real target formats.
//
// The idea (a command-line pipeline check against the real driver) comes from GaelQuinta's
// minecraft-metal-shaders, tools/shade/pipecheck.m (Apache-2.0). This is our own code.
// Build: clang -fobjc-arc -O1 -framework Foundation -framework Metal pipecheck.m -o pipecheck
#import <Foundation/Foundation.h>
#import <Metal/Metal.h>

static NSString *json(id obj) {
    NSData *d = [NSJSONSerialization dataWithJSONObject:obj options:NSJSONWritingSortedKeys error:nil];
    return [[NSString alloc] initWithData:d encoding:NSUTF8StringEncoding];
}

static NSString *firstLines(NSString *s, int n) {
    NSArray *lines = [s componentsSeparatedByString:@"\n"];
    NSMutableArray *keep = [NSMutableArray array];
    for (NSString *l in lines) {
        if ([l rangeOfString:@"error"].location != NSNotFound) [keep addObject:l];
        if ((int) keep.count >= n) break;
    }
    if (keep.count == 0 && lines.count > 0) [keep addObject:lines[0]];
    return [keep componentsJoinedByString:@" | "];
}

// [[color(n)]] outputs of the fragment source's output struct, with their scalar kind.
static NSArray *colorOutputs(NSString *src) {
    NSRegularExpression *re = [NSRegularExpression regularExpressionWithPattern:
        @"\\b(float|half|uint|int|ushort|short)[234]?\\s+\\w+\\s*\\[\\[color\\((\\d+)\\)\\]\\]" options:0 error:nil];
    NSMutableArray *out = [NSMutableArray array];
    for (NSTextCheckingResult *m in [re matchesInString:src options:0 range:NSMakeRange(0, src.length)]) {
        [out addObject:@{@"index": @([[src substringWithRange:[m rangeAtIndex:2]] intValue]),
                         @"kind": [src substringWithRange:[m rangeAtIndex:1]]}];
    }
    return out;
}

static MTLVertexFormat formatFor(MTLDataType t, int *size) {
    switch (t) {
        case MTLDataTypeFloat: *size = 4; return MTLVertexFormatFloat;
        case MTLDataTypeFloat2: *size = 8; return MTLVertexFormatFloat2;
        case MTLDataTypeFloat3: *size = 12; return MTLVertexFormatFloat3;
        case MTLDataTypeFloat4: *size = 16; return MTLVertexFormatFloat4;
        case MTLDataTypeInt: *size = 4; return MTLVertexFormatInt;
        case MTLDataTypeInt2: *size = 8; return MTLVertexFormatInt2;
        case MTLDataTypeInt3: *size = 12; return MTLVertexFormatInt3;
        case MTLDataTypeInt4: *size = 16; return MTLVertexFormatInt4;
        case MTLDataTypeUInt: *size = 4; return MTLVertexFormatUInt;
        case MTLDataTypeUInt2: *size = 8; return MTLVertexFormatUInt2;
        case MTLDataTypeUInt3: *size = 12; return MTLVertexFormatUInt3;
        case MTLDataTypeUInt4: *size = 16; return MTLVertexFormatUInt4;
        case MTLDataTypeShort2: *size = 4; return MTLVertexFormatShort2;
        case MTLDataTypeShort4: *size = 8; return MTLVertexFormatShort4;
        case MTLDataTypeUShort2: *size = 4; return MTLVertexFormatUShort2;
        case MTLDataTypeUShort4: *size = 8; return MTLVertexFormatUShort4;
        default: *size = 16; return MTLVertexFormatFloat4;
    }
}

static NSDictionary *check(id<MTLDevice> dev, NSString *name, NSString *vsPath, NSString *fsPath) {
    NSMutableDictionary *r = [@{@"name": name} mutableCopy];
    NSString *vsSrc = [NSString stringWithContentsOfFile:vsPath encoding:NSUTF8StringEncoding error:nil];
    NSString *fsSrc = [NSString stringWithContentsOfFile:fsPath encoding:NSUTF8StringEncoding error:nil];
    if (!vsSrc || !fsSrc) { r[@"ok"] = @NO; r[@"error"] = @"missing file"; return r; }
    NSError *e = nil;
    id<MTLLibrary> vl = [dev newLibraryWithSource:vsSrc options:nil error:&e];
    if (!vl) { r[@"ok"] = @NO; r[@"error"] = [@"vertex compile: " stringByAppendingString:firstLines(e.localizedDescription, 2)]; return r; }
    e = nil;
    id<MTLLibrary> fl = [dev newLibraryWithSource:fsSrc options:nil error:&e];
    if (!fl) { r[@"ok"] = @NO; r[@"error"] = [@"fragment compile: " stringByAppendingString:firstLines(e.localizedDescription, 2)]; return r; }
    id<MTLFunction> vf = [vl newFunctionWithName:vl.functionNames.firstObject];
    id<MTLFunction> ff = [fl newFunctionWithName:fl.functionNames.firstObject];
    if (!vf || !ff) { r[@"ok"] = @NO; r[@"error"] = @"no entry point"; return r; }

    MTLRenderPipelineDescriptor *pd = [MTLRenderPipelineDescriptor new];
    pd.vertexFunction = vf;
    pd.fragmentFunction = ff;
    MTLVertexDescriptor *vd = [MTLVertexDescriptor vertexDescriptor];
    NSMutableArray *inputs = [NSMutableArray array];
    // Buffer 30 is far from the low indices spirv-cross gives uniform buffers.
    int offset = 0;
    for (MTLVertexAttribute *a in vf.vertexAttributes) {
        if (!a.active) continue;
        int size;
        MTLVertexFormat f = formatFor(a.attributeType, &size);
        vd.attributes[a.attributeIndex].bufferIndex = 30;
        vd.attributes[a.attributeIndex].offset = offset;
        vd.attributes[a.attributeIndex].format = f;
        offset += size;
        [inputs addObject:a.name];
    }
    if (offset > 0) {
        vd.layouts[30].stride = (NSUInteger) ((offset + 3) & ~3);
        pd.vertexDescriptor = vd;
    }
    NSArray *outs = colorOutputs(fsSrc);
    NSMutableArray *outIdx = [NSMutableArray array];
    for (NSDictionary *o in outs) {
        int i = [o[@"index"] intValue];
        NSString *k = o[@"kind"];
        pd.colorAttachments[i].pixelFormat = [k hasPrefix:@"u"] ? MTLPixelFormatRGBA32Uint
            : ([k isEqualToString:@"int"] || [k isEqualToString:@"short"]) ? MTLPixelFormatRGBA32Sint : MTLPixelFormatRGBA16Float;
        [outIdx addObject:@(i)];
    }
    pd.depthAttachmentPixelFormat = MTLPixelFormatDepth32Float;
    r[@"vertexInputs"] = [inputs sortedArrayUsingSelector:@selector(compare:)];
    r[@"colorOutputs"] = [outIdx sortedArrayUsingSelector:@selector(compare:)];

    e = nil;
    MTLRenderPipelineReflection *refl = nil;
    id<MTLRenderPipelineState> pso = [dev newRenderPipelineStateWithDescriptor:pd options:MTLPipelineOptionBindingInfo reflection:&refl error:&e];
    if (!pso) { r[@"ok"] = @NO; r[@"error"] = [@"pipeline: " stringByAppendingString:firstLines(e.localizedDescription, 2)]; return r; }
    NSMutableArray *vb = [NSMutableArray array], *ft = [NSMutableArray array], *fbuf = [NSMutableArray array];
    for (id<MTLBinding> b in refl.vertexBindings) if (b.used && b.type == MTLBindingTypeBuffer) [vb addObject:b.name];
    for (id<MTLBinding> b in refl.fragmentBindings) {
        if (!b.used) continue;
        if (b.type == MTLBindingTypeTexture) [ft addObject:b.name];
        else if (b.type == MTLBindingTypeBuffer) [fbuf addObject:b.name];
    }
    for (id<MTLBinding> b in refl.vertexBindings) if (b.used && b.type == MTLBindingTypeTexture) [ft addObject:[@"vs:" stringByAppendingString:b.name]];
    r[@"ok"] = @YES;
    r[@"vertexBuffers"] = [vb sortedArrayUsingSelector:@selector(compare:)];
    r[@"fragmentBuffers"] = [fbuf sortedArrayUsingSelector:@selector(compare:)];
    r[@"textures"] = [ft sortedArrayUsingSelector:@selector(compare:)];
    return r;
}

int main(int argc, const char **argv) {
    @autoreleasepool {
        if (argc != 2) { fprintf(stderr, "usage: pipecheck <list.tsv>  (name<TAB>vertex.metal<TAB>fragment.metal per line)\n"); return 2; }
        id<MTLDevice> dev = MTLCreateSystemDefaultDevice();
        NSString *list = [NSString stringWithContentsOfFile:[NSString stringWithUTF8String:argv[1]] encoding:NSUTF8StringEncoding error:nil];
        if (!list) { fprintf(stderr, "cannot read %s\n", argv[1]); return 2; }
        int fail = 0;
        for (NSString *line in [list componentsSeparatedByString:@"\n"]) {
            NSArray *f = [line componentsSeparatedByString:@"\t"];
            if (f.count < 3) continue;
            @autoreleasepool {
                NSDictionary *r = check(dev, f[0], f[1], f[2]);
                if (![r[@"ok"] boolValue]) fail++;
                printf("%s\n", json(r).UTF8String);
            }
        }
        return fail == 0 ? 0 : 1;
    }
}
