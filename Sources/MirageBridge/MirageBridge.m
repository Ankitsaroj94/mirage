//
//  MirageBridge.m
//

#import "include/MirageBridge.h"

static NSString *const MRGErrorDomain = @"com.voxturr.mirage.virtualdisplay";

#pragma mark - Private CoreGraphics interfaces

@interface CGVirtualDisplayMode : NSObject
- (instancetype)initWithWidth:(uint32_t)width height:(uint32_t)height refreshRate:(double)refreshRate;
@end

@interface CGVirtualDisplaySettings : NSObject
@property (nonatomic, strong) NSArray *modes;
@property (nonatomic) uint32_t hiDPI;
@property (nonatomic) uint32_t rotation;
@end

@interface CGVirtualDisplayDescriptor : NSObject
@property (nonatomic, strong) NSString *name;
@property (nonatomic) CGSize sizeInMillimeters;
@property (nonatomic) uint32_t maxPixelsWide;
@property (nonatomic) uint32_t maxPixelsHigh;
@property (nonatomic) uint32_t vendorID;
@property (nonatomic) uint32_t productID;
@property (nonatomic) uint32_t serialNum;
@property (nonatomic) CGPoint redPrimary;
@property (nonatomic) CGPoint greenPrimary;
@property (nonatomic) CGPoint bluePrimary;
@property (nonatomic) CGPoint whitePoint;
@property (nonatomic, strong) dispatch_queue_t queue;
@property (nonatomic, copy) void (^terminationHandler)(id a, id b);
- (void)setDispatchQueue:(dispatch_queue_t)queue;
@end

@interface CGVirtualDisplay : NSObject
@property (nonatomic, readonly) uint32_t displayID;
- (instancetype)initWithDescriptor:(CGVirtualDisplayDescriptor *)descriptor;
- (BOOL)applySettings:(CGVirtualDisplaySettings *)settings;
@end

#pragma mark -

@implementation MRGVirtualDisplay {
    CGVirtualDisplay *_display;
    BOOL _invalidated;
}

+ (BOOL)isSupported {
    return NSClassFromString(@"CGVirtualDisplay") != nil
        && NSClassFromString(@"CGVirtualDisplayDescriptor") != nil
        && NSClassFromString(@"CGVirtualDisplaySettings") != nil
        && NSClassFromString(@"CGVirtualDisplayMode") != nil;
}

static NSError *MRGError(NSInteger code, NSString *msg) {
    return [NSError errorWithDomain:MRGErrorDomain code:code
                           userInfo:@{NSLocalizedDescriptionKey: msg}];
}

+ (nullable MRGVirtualDisplay *)displayWithName:(NSString *)name
                              pixelWidth:(uint32_t)pixelWidth
                             pixelHeight:(uint32_t)pixelHeight
                              pointWidth:(uint32_t)pointWidth
                             pointHeight:(uint32_t)pointHeight
                             refreshRate:(double)refreshRate
                                   error:(NSError **)error {
    if (![self isSupported]) {
        if (error) *error = MRGError(1, @"CGVirtualDisplay is not available on this version of macOS. "
                                        @"Apple may have removed the private API.");
        return nil;
    }

    // Preferred identity: derived from the geometry, so macOS recognises this as the same
    // monitor next time and restores its place in the arrangement.
    uint32_t preferred = (pixelWidth << 16) ^ pixelHeight;

    // A display with this identity may still be tearing down from a previous session, in
    // which case WindowServer refuses the new one. Give it a moment, then take a unique
    // identity rather than failing the connection outright.
    for (int attempt = 0; attempt < 3; attempt++) {
        uint32_t serial = (attempt < 2) ? preferred : (preferred ^ (uint32_t)arc4random());
        MRGVirtualDisplay *display = [self displayWithName:name
                                                pixelWidth:pixelWidth pixelHeight:pixelHeight
                                                pointWidth:pointWidth pointHeight:pointHeight
                                               refreshRate:refreshRate
                                              serialNumber:serial
                                                     error:error];
        if (display) return display;
        if (attempt == 0) usleep(600 * 1000);
    }
    return nil;
}

+ (nullable MRGVirtualDisplay *)displayWithName:(NSString *)name
                              pixelWidth:(uint32_t)pixelWidth
                             pixelHeight:(uint32_t)pixelHeight
                              pointWidth:(uint32_t)pointWidth
                             pointHeight:(uint32_t)pointHeight
                             refreshRate:(double)refreshRate
                            serialNumber:(uint32_t)serialNumber
                                   error:(NSError **)error {

    CGVirtualDisplayDescriptor *descriptor = [[NSClassFromString(@"CGVirtualDisplayDescriptor") alloc] init];
    descriptor.name = name;

    // The backing store is described in PIXELS here...
    descriptor.maxPixelsWide = pixelWidth;
    descriptor.maxPixelsHigh = pixelHeight;

    // Physical size only affects the "scaled" UI copy and the reported DPI. Deriving it
    // from the point size at ~110 DPI keeps macOS from offering silly scaled modes.
    descriptor.sizeInMillimeters = CGSizeMake(pointWidth / 110.0 * 25.4, pointHeight / 110.0 * 25.4);

    descriptor.vendorID  = 0x4D52;  // 'MR'
    descriptor.productID = 0x4744;  // 'GD'
    // Stable per-geometry serial, so macOS remembers where you put the display and at what
    // scale. The cost is that two displays of identical geometry share an EDID identity and
    // the second is refused — see the retry below, which falls back to a unique serial.
    descriptor.serialNum = serialNumber;

    // sRGB / Rec.709 primaries. Matches what we tag the encoded video with, so the
    // receiving Mac does not apply a colour transform that would smear text edges.
    descriptor.redPrimary   = CGPointMake(0.6400, 0.3300);
    descriptor.greenPrimary = CGPointMake(0.3000, 0.6000);
    descriptor.bluePrimary  = CGPointMake(0.1500, 0.0600);
    descriptor.whitePoint   = CGPointMake(0.3127, 0.3290);

    // The main queue, matching what CoreGraphics expects. A dedicated serial queue was
    // tried here and made mode enumeration fail outright, so this is deliberate.
    dispatch_queue_t queue = dispatch_get_main_queue();
    if ([descriptor respondsToSelector:@selector(setDispatchQueue:)]) {
        [descriptor setDispatchQueue:queue];
    } else {
        descriptor.queue = queue;
    }

    MRGVirtualDisplay *wrapper = [[MRGVirtualDisplay alloc] init];
    __weak MRGVirtualDisplay *weakWrapper = wrapper;
    descriptor.terminationHandler = ^(id a, id b) {
        (void)a; (void)b;
        MRGVirtualDisplay *strong = weakWrapper;
        if (strong) strong->_invalidated = YES;
    };

    CGVirtualDisplay *display = [[NSClassFromString(@"CGVirtualDisplay") alloc] initWithDescriptor:descriptor];
    if (!display) {
        if (error) *error = MRGError(2, @"WindowServer refused to create the display. Another display "
                                        @"with the same size may still be shutting down.");
        return nil;
    }

    CGVirtualDisplaySettings *settings = [[NSClassFromString(@"CGVirtualDisplaySettings") alloc] init];
    settings.hiDPI = 1;
    settings.rotation = 0;

    // ...but the mode list is described in POINTS. With hiDPI=1 macOS synthesises the
    // matching 2x mode (pointWidth x pointHeight @ pixelWidth x pixelHeight).
    CGVirtualDisplayMode *mode = [[NSClassFromString(@"CGVirtualDisplayMode") alloc]
                                  initWithWidth:pointWidth height:pointHeight refreshRate:refreshRate];
    if (!mode) {
        if (error) *error = MRGError(3, @"Could not build a CGVirtualDisplayMode.");
        return nil;
    }
    settings.modes = @[mode];

    if (![display applySettings:settings]) {
        if (error) *error = MRGError(4, @"applySettings: was rejected by WindowServer.");
        return nil;
    }

    CGDirectDisplayID displayID = display.displayID;
    if (displayID == 0) {
        if (error) *error = MRGError(5, @"WindowServer did not assign a display ID.");
        return nil;
    }

    wrapper->_display     = display;
    wrapper->_displayID   = displayID;
    wrapper->_pixelWidth  = pixelWidth;
    wrapper->_pixelHeight = pixelHeight;
    wrapper->_pointWidth  = pointWidth;
    wrapper->_pointHeight = pointHeight;

    // macOS attaches the display in a 1x mode by default. Left alone the stream would
    // carry a non-Retina desktop upscaled to the panel — soft, fringed text. Force the
    // synthesised 2x mode.
    wrapper->_isHiDPI = [wrapper selectHiDPIMode];

    return wrapper;
}

/// Waits briefly for the mode list to populate, then switches to the mode whose
/// pixel size is our backing store and whose point size is half of it.
- (BOOL)selectHiDPIMode {
    NSDictionary *options = @{ (__bridge NSString *)kCGDisplayShowDuplicateLowResolutionModes: @YES };

    for (int attempt = 0; attempt < 40; attempt++) {   // up to ~2s
        CFArrayRef modes = CGDisplayCopyAllDisplayModes(_displayID, (__bridge CFDictionaryRef)options);
        CGDisplayModeRef best = NULL;

        for (CFIndex i = 0; modes && i < CFArrayGetCount(modes); i++) {
            CGDisplayModeRef m = (CGDisplayModeRef)CFArrayGetValueAtIndex(modes, i);
            if (CGDisplayModeGetPixelWidth(m)  == _pixelWidth &&
                CGDisplayModeGetPixelHeight(m) == _pixelHeight &&
                CGDisplayModeGetWidth(m)       == _pointWidth &&
                CGDisplayModeGetHeight(m)      == _pointHeight) {
                best = m;
                break;
            }
        }

        if (best) {
            CGDisplayConfigRef config = NULL;
            if (CGBeginDisplayConfiguration(&config) == kCGErrorSuccess) {
                CGConfigureDisplayWithDisplayMode(config, _displayID, best, NULL);
                CGError err = CGCompleteDisplayConfiguration(config, kCGConfigurePermanently);
                if (modes) CFRelease(modes);
                if (err != kCGErrorSuccess) return NO;

                CGDisplayModeRef current = CGDisplayCopyDisplayMode(_displayID);
                BOOL ok = current && CGDisplayModeGetPixelWidth(current) == _pixelWidth
                                  && CGDisplayModeGetWidth(current)      == _pointWidth;
                if (current) CGDisplayModeRelease(current);
                return ok;
            }
        }
        if (modes) CFRelease(modes);
        usleep(50 * 1000);
    }
    return NO;
}

- (void)invalidate {
    _display = nil;      // ARC release tears down the display
    _invalidated = YES;
}

- (void)dealloc {
    [self invalidate];
}

@end
