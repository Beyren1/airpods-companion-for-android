using System.Runtime.InteropServices;

namespace PodsCompanion.Core.Bluetooth;

/// <summary>
/// Подключает и отключает звук Bluetooth-наушников так же, как кнопки «Подключить» и «Отключить»
/// в параметрах Windows. Драйвер для этого не нужен: у звукового драйвера Bluetooth есть
/// команды KSPROPERTY_ONESHOT_RECONNECT и KSPROPERTY_ONESHOT_DISCONNECT, и до них можно добраться
/// из обычной программы через Core Audio API (звуковые устройства → топология → фильтр Bluetooth).
/// </summary>
public static class BluetoothAudioControl
{
    /// <summary>Подключить звук наушников. Возвращает число фильтров, которым отправлена команда.</summary>
    public static int Connect(string addressHex, string name, Action<string>? log = null) =>
        Send(addressHex, name, KsPropertyOneshotReconnect, log);

    /// <summary>Отключить звук (A2DP и гарнитуру) наушников.</summary>
    public static int Disconnect(string addressHex, string name, Action<string>? log = null) =>
        Send(addressHex, name, KsPropertyOneshotDisconnect, log);

    /// <summary>
    /// Путь фильтра звукового драйвера Bluetooth выглядит так:
    /// <c>\\?\...BTHENUM#{0000110b-...}_VID&amp;0001004c_PID&amp;2014#8&amp;...&amp;0&amp;AC90856D3A8E_C00000000#{...}</c>
    /// (для гарнитуры — BTHHFENUM). Адрес наушников входит в него 12 цифрами без разделителей.
    /// </summary>
    internal static bool IsFilterOf(string filterId, string addressHex) =>
        IsBluetoothFilter(filterId) && filterId.Contains(addressHex, StringComparison.OrdinalIgnoreCase);

    internal static bool IsBluetoothFilter(string filterId) =>
        filterId.Contains("BTHENUM", StringComparison.OrdinalIgnoreCase) ||
        filterId.Contains("BTHHFENUM", StringComparison.OrdinalIgnoreCase);

    /// <summary>
    /// Звуковое устройство Windows называет по имени наушников: «Наушники (AirPods Max)»,
    /// гарнитуру — «Головной телефон (AirPods Max Hands-Free)».
    /// Запасной способ найти их, если адреса в пути фильтра нет.
    /// </summary>
    internal static bool NameMatches(string endpointName, string podsName) =>
        podsName.Length > 0 &&
        (endpointName.Contains($"({podsName})", StringComparison.OrdinalIgnoreCase) ||
         endpointName.Contains($"({podsName} Hands-Free)", StringComparison.OrdinalIgnoreCase));

    private static int Send(string addressHex, string podsName, uint propertyId, Action<string>? log)
    {
        int sent = 0;
        var done = new HashSet<string>(StringComparer.OrdinalIgnoreCase);
        var enumerator = (IMMDeviceEnumerator)new MMDeviceEnumeratorComObject();
        try
        {
            // Все устройства, включая отключённые: у сопряжённых, но не подключённых наушников они есть.
            Check(enumerator.EnumAudioEndpoints(EDataFlowAll, DeviceStateMaskAll, out var collection));
            Check(collection.GetCount(out uint count));
            for (uint i = 0; i < count; i++)
            {
                if (collection.Item(i, out var endpoint) != 0) continue;
                string name = FriendlyName(endpoint);
                try
                {
                    string? error = TryGetFilter(endpoint, out var part, out var filterId);
                    bool nameMatch = NameMatches(name, podsName);
                    if (error != null)
                    {
                        // Подробности только про похожие устройства, чтобы не засорять журнал колонками и микрофонами.
                        if (nameMatch) log?.Invoke($"  «{name}»: {error}");
                        continue;
                    }
                    // У гарнитуры (BTHHFENUM) адреса в пути нет, её находим по имени устройства.
                    if ((IsFilterOf(filterId, addressHex) || nameMatch && IsBluetoothFilter(filterId)) && done.Add(filterId))
                        sent += Fire(enumerator, part, filterId, propertyId, log);
                }
                catch (Exception e) when (e is COMException or InvalidCastException)
                {
                    log?.Invoke($"  «{name}»: {e.GetType().Name} 0x{e.HResult:X8}");
                }
                finally
                {
                    Marshal.ReleaseComObject(endpoint);
                }
            }
            Marshal.ReleaseComObject(collection);
            if (done.Count == 0) log?.Invoke($"  среди {count} звуковых устройств этих наушников нет");
        }
        finally
        {
            Marshal.ReleaseComObject(enumerator);
        }
        return sent;
    }

    private static int Fire(IMMDeviceEnumerator enumerator, IPart part, string filterId, uint propertyId, Action<string>? log)
    {
        int hr = SendProperty(enumerator, part, filterId, propertyId);
        log?.Invoke($"  {(filterId.Contains("bthhfenum", StringComparison.OrdinalIgnoreCase) ? "гарнитура" : "стерео")}: 0x{hr:X8}");
        return hr == 0 ? 1 : 0;
    }

    /// <returns>null, если фильтр найден; иначе на каком шаге не вышло.</returns>
    private static string? TryGetFilter(IMMDevice endpoint, out IPart part, out string filterId)
    {
        part = null!;
        filterId = "";
        var topologyIid = typeof(IDeviceTopology).GUID;
        int hr = endpoint.Activate(ref topologyIid, ClsctxAll, IntPtr.Zero, out var topologyObject);
        if (hr != 0) return $"топология 0x{hr:X8}";
        var topology = (IDeviceTopology)topologyObject;
        hr = topology.GetConnector(0, out var connector);
        if (hr != 0) return $"разъём 0x{hr:X8}";
        // Разъём звукового устройства соединён с разъёмом фильтра драйвера Bluetooth.
        hr = connector.GetConnectedTo(out var other);
        if (hr != 0) return $"соединение разъёма 0x{hr:X8}";
        part = (IPart)other;
        hr = part.GetTopologyObject(out var filterTopology);
        if (hr != 0) return $"топология фильтра 0x{hr:X8}";
        hr = filterTopology.GetDeviceId(out filterId);
        return hr != 0 ? $"путь фильтра 0x{hr:X8}" : null;
    }

    /// <summary>
    /// Отправляет команду фильтру драйвера. IKsControl фильтра достаём через само устройство фильтра
    /// (GetDevice по его пути): у части разъёма (IPart) Windows 11 этот интерфейс не отдаёт (E_NOINTERFACE).
    /// </summary>
    /// <returns>HRESULT: 0 — команда принята драйвером.</returns>
    private static int SendProperty(IMMDeviceEnumerator enumerator, IPart part, string filterId, uint propertyId)
    {
        var ksControlIid = typeof(IKsControl).GUID;
        int hr = enumerator.GetDevice(filterId, out var filterDevice);
        if (hr == 0)
        {
            hr = filterDevice.Activate(ref ksControlIid, ClsctxAll, IntPtr.Zero, out var fromDevice);
            Marshal.ReleaseComObject(filterDevice);
            if (hr == 0) return SendKs(fromDevice, propertyId);
        }
        int partHr = part.Activate(ClsctxInprocServer, ref ksControlIid, out var fromPart);
        return partHr == 0 ? SendKs(fromPart, propertyId) : hr;
    }

    private static int SendKs(object ksObject, uint propertyId)
    {
        var ks = (IKsControl)ksObject;
        var property = new KsProperty { Set = KsPropSetIdBtAudio, Id = propertyId, Flags = KsPropertyTypeGet };
        int hr = ks.KsProperty(ref property, (uint)Marshal.SizeOf<KsProperty>(), IntPtr.Zero, 0, out _);
        Marshal.ReleaseComObject(ks);
        return hr;
    }

    /// <summary>Имя звукового устройства, как в настройках звука Windows.</summary>
    private static string FriendlyName(IMMDevice endpoint)
    {
        if (endpoint.OpenPropertyStore(StgmRead, out var storePtr) != 0) return "?";
        var store = (IPropertyStore)Marshal.GetObjectForIUnknown(storePtr);
        Marshal.Release(storePtr);
        try
        {
            var key = PkeyDeviceFriendlyName;
            if (store.GetValue(ref key, out var value) != 0) return "?";
            try
            {
                return value.Type == VtLpwstr ? Marshal.PtrToStringUni(value.Pointer) ?? "?" : "?";
            }
            finally
            {
                PropVariantClear(ref value);
            }
        }
        finally
        {
            Marshal.ReleaseComObject(store);
        }
    }

    private static void Check(int hr)
    {
        if (hr != 0) Marshal.ThrowExceptionForHR(hr);
    }

    // ---------- Определения из mmdeviceapi.h, devicetopology.h, ks.h, ksmedia.h ----------

    private const int EDataFlowAll = 2;
    private const uint DeviceStateMaskAll = 0xF;
    private const uint ClsctxInprocServer = 0x1;
    private const uint ClsctxAll = 0x17;
    private const uint StgmRead = 0;
    private const ushort VtLpwstr = 31;

    private static PropertyKey PkeyDeviceFriendlyName = new()
    {
        FormatId = new Guid("A45C254E-DF1C-4EFD-8020-67D146A850E0"),
        PropertyId = 14,
    };

    [StructLayout(LayoutKind.Sequential)]
    private struct PropertyKey
    {
        public Guid FormatId;
        public uint PropertyId;
    }

    /// <summary>PROPVARIANT: тип, три зарезервированных поля и значение (здесь — указатель на строку).</summary>
    [StructLayout(LayoutKind.Sequential)]
    private struct PropVariant
    {
        public ushort Type;
        public ushort Reserved1;
        public ushort Reserved2;
        public ushort Reserved3;
        public IntPtr Pointer;
        public IntPtr Extra;
    }

    [DllImport("ole32.dll")]
    private static extern int PropVariantClear(ref PropVariant value);

    [ComImport, Guid("886D8EEB-8CF2-4446-8D02-CDBA1DBDCF99"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
    private interface IPropertyStore
    {
        [PreserveSig] int GetCount(out uint count);
        [PreserveSig] int GetAt(uint index, out PropertyKey key);
        [PreserveSig] int GetValue(ref PropertyKey key, out PropVariant value);
        [PreserveSig] int SetValue(ref PropertyKey key, ref PropVariant value);
        [PreserveSig] int Commit();
    }

    private static readonly Guid KsPropSetIdBtAudio = new("7FA06C40-B8F6-4C7E-8556-E8C33A12E54D");
    private const uint KsPropertyOneshotReconnect = 0;
    private const uint KsPropertyOneshotDisconnect = 1;
    private const uint KsPropertyTypeGet = 0x1;

    [StructLayout(LayoutKind.Sequential)]
    private struct KsProperty
    {
        public Guid Set;
        public uint Id;
        public uint Flags;
    }

    [ComImport, Guid("BCDE0395-E52F-467C-8E3D-C4579291692E")]
    private class MMDeviceEnumeratorComObject;

    // Порядок методов важен: это таблица виртуальных функций COM-интерфейса, как в C++.
    [ComImport, Guid("A95664D2-9614-4F35-A746-DE8DB63617E6"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
    private interface IMMDeviceEnumerator
    {
        [PreserveSig] int EnumAudioEndpoints(int dataFlow, uint stateMask, out IMMDeviceCollection devices);
        [PreserveSig] int GetDefaultAudioEndpoint(int dataFlow, int role, out IMMDevice endpoint);
        [PreserveSig] int GetDevice([MarshalAs(UnmanagedType.LPWStr)] string id, out IMMDevice device);
        [PreserveSig] int RegisterEndpointNotificationCallback(IntPtr client);
        [PreserveSig] int UnregisterEndpointNotificationCallback(IntPtr client);
    }

    [ComImport, Guid("0BD7A1BE-7A1A-44DB-8397-CC5392387B5E"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
    private interface IMMDeviceCollection
    {
        [PreserveSig] int GetCount(out uint count);
        [PreserveSig] int Item(uint index, out IMMDevice device);
    }

    [ComImport, Guid("D666063F-1587-4E43-81F1-B948E807363F"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
    private interface IMMDevice
    {
        [PreserveSig] int Activate(ref Guid iid, uint clsCtx, IntPtr activationParams, [MarshalAs(UnmanagedType.IUnknown)] out object result);
        [PreserveSig] int OpenPropertyStore(uint access, out IntPtr properties);
        [PreserveSig] int GetId([MarshalAs(UnmanagedType.LPWStr)] out string id);
        [PreserveSig] int GetState(out uint state);
    }

    [ComImport, Guid("2A07407E-6497-4A18-9787-32F79BD0D98F"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
    private interface IDeviceTopology
    {
        [PreserveSig] int GetConnectorCount(out uint count);
        [PreserveSig] int GetConnector(uint index, out IConnector connector);
        [PreserveSig] int GetSubunitCount(out uint count);
        [PreserveSig] int GetSubunit(uint index, out IntPtr subunit);
        [PreserveSig] int GetPartById(uint id, out IntPtr part);
        [PreserveSig] int GetDeviceId([MarshalAs(UnmanagedType.LPWStr)] out string deviceId);
        [PreserveSig] int GetSignalPath(IntPtr from, IntPtr to, [MarshalAs(UnmanagedType.Bool)] bool rejectMixedPaths, out IntPtr parts);
    }

    [ComImport, Guid("9C2C4058-23F5-41DE-877A-DF3AF236A09E"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
    private interface IConnector
    {
        [PreserveSig] int GetConnectorType(out int type);
        [PreserveSig] int GetDataFlow(out int flow);
        [PreserveSig] int ConnectTo(IConnector other);
        [PreserveSig] int Disconnect();
        [PreserveSig] int IsConnected([MarshalAs(UnmanagedType.Bool)] out bool connected);
        [PreserveSig] int GetConnectedTo(out IConnector other);
        [PreserveSig] int GetConnectorIdConnectedTo([MarshalAs(UnmanagedType.LPWStr)] out string id);
        [PreserveSig] int GetDeviceIdConnectedTo([MarshalAs(UnmanagedType.LPWStr)] out string id);
    }

    [ComImport, Guid("AE2DE0E4-5BCA-4F2D-AA46-5D13F8FDB3A9"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
    private interface IPart
    {
        [PreserveSig] int GetName([MarshalAs(UnmanagedType.LPWStr)] out string name);
        [PreserveSig] int GetLocalId(out uint id);
        [PreserveSig] int GetGlobalId([MarshalAs(UnmanagedType.LPWStr)] out string id);
        [PreserveSig] int GetPartType(out int type);
        [PreserveSig] int GetSubType(out Guid subType);
        [PreserveSig] int GetControlInterfaceCount(out uint count);
        [PreserveSig] int GetControlInterface(uint index, out IntPtr control);
        [PreserveSig] int EnumPartsIncoming(out IntPtr parts);
        [PreserveSig] int EnumPartsOutgoing(out IntPtr parts);
        [PreserveSig] int GetTopologyObject(out IDeviceTopology topology);
        [PreserveSig] int Activate(uint clsContext, ref Guid iid, [MarshalAs(UnmanagedType.IUnknown)] out object result);
        [PreserveSig] int RegisterControlChangeCallback(ref Guid iid, IntPtr callback);
        [PreserveSig] int UnregisterControlChangeCallback(IntPtr callback);
    }

    [ComImport, Guid("28F54685-06FD-11D2-B27A-00A0C9223196"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
    private interface IKsControl
    {
        [PreserveSig] int KsProperty(ref KsProperty property, uint propertyLength, IntPtr data, uint dataLength, out uint bytesReturned);
        [PreserveSig] int KsMethod(IntPtr method, uint methodLength, IntPtr data, uint dataLength, out uint bytesReturned);
        [PreserveSig] int KsEvent(IntPtr ksEvent, uint eventLength, IntPtr data, uint dataLength, out uint bytesReturned);
    }
}
