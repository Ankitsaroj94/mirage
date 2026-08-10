//
//  MirageBridge.h
//  Objective-C shim over the private CoreGraphics virtual-display API.
//
//  The CGVirtualDisplay* classes live inside CoreGraphics but are not exported as
//  link-time symbols, so everything here goes through NSClassFromString. Declaring
//  the interfaces gives us type checking without creating an undefined symbol.
//
//  Verified present on macOS 26.5 (and unchanged in shape since macOS 11).
//

#import <Foundation/Foundation.h>
#import <CoreGraphics/CoreGraphics.h>

NS_ASSUME_NONNULL_BEGIN

/// A live virtual display. Deallocating this object tears the display down and
/// macOS re-arranges the desktop back to its previous layout.
@interface MRGVirtualDisplay : NSObject

/// The CGDirectDisplayID macOS assigned to the virtual display.
@property (nonatomic, readonly) CGDirectDisplayID displayID;

/// Backing-store size in pixels (e.g. 2880x1800).
@property (nonatomic, readonly) uint32_t pixelWidth;
@property (nonatomic, readonly) uint32_t pixelHeight;

/// Logical size in points (e.g. 1440x900). Half of the pixel size for a 2x display.
@property (nonatomic, readonly) uint32_t pointWidth;
@property (nonatomic, readonly) uint32_t pointHeight;

/// YES when the display actually settled on a 2x HiDPI mode. If this is NO the
/// stream still works but text will be rendered at 1x and look soft.
@property (nonatomic, readonly) BOOL isHiDPI;

/// Creates a HiDPI virtual display.
///
/// @param name          Name shown in System Settings > Displays.
/// @param pixelWidth    Backing store width in pixels (the video stream size).
/// @param pixelHeight   Backing store height in pixels.
/// @param pointWidth    Logical width in points. Pass pixelWidth/2 for a 2x display.
/// @param pointHeight   Logical height in points.
/// @param refreshRate   Refresh rate in Hz.
/// @param error         Populated with a human-readable reason on failure.
///
/// The point/pixel split is load-bearing: the private API takes the *pixel*
/// dimensions in maxPixelsWide/High and the *point* dimensions in the mode list.
/// Passing pixel dimensions in the mode (a common mistake) yields a 1x-only
/// display and permanently soft text.
+ (nullable MRGVirtualDisplay *)displayWithName:(NSString *)name
                              pixelWidth:(uint32_t)pixelWidth
                             pixelHeight:(uint32_t)pixelHeight
                              pointWidth:(uint32_t)pointWidth
                             pointHeight:(uint32_t)pointHeight
                             refreshRate:(double)refreshRate
                                   error:(NSError *_Nullable *_Nullable)error
    NS_SWIFT_NAME(make(name:pixelWidth:pixelHeight:pointWidth:pointHeight:refreshRate:));

/// YES when the private classes are resolvable on this OS build.
@property (class, nonatomic, readonly, getter=isSupported) BOOL supported;

/// Explicitly tears down the display. Also called from -dealloc.
- (void)invalidate;

@end

NS_ASSUME_NONNULL_END
