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
    /// <summary>Подключить звук наушников с адресом <paramref name="addressHex"/>. Возвращает число фильтров, которым отправлена команда.</summary>
    public static int Connect(string addressHex) => Send(addressHex, KsPropertyOneshotReconnect);

    /// <summary>Отключить звук (A2DP и гарнитуру) наушников с адресом <paramref name="addressHex"/>.</summary>
    public static int Disconnect(string addressHex) => Send(addressHex, KsPropertyOneshotDisconnect);

    /// <summary>
    /// Путь фильтра звукового драйвера Bluetooth выглядит так:
    /// <c>\\?\...BTHENUM#{0000110b-...}_VID&amp;0001004c_PID&amp;2014#8&amp;...&amp;0&amp;AC90856D3A8E_C00000000#{...}</c>.
    /// Адрес наушников входит в него 12 цифрами без разделителей.
    /// </summary>
    internal static bool IsFilterOf(string filterId, string addressHex) =>
        filterId.Contains("BTHENUM", StringComparison.OrdinalIgnoreCase) &&
        filterId.Contains(addressHex, StringComparison.OrdinalIgnoreCase);

    private static int Send(string addressHex, uint propertyId)
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
                try
                {
                    if (TryGetBluetoothFilter(endpoint, out var part, out var filterId) &&
                        IsFilterOf(filterId, addressHex) && done.Add(filterId))
                    {
                        if (SendProperty(part, propertyId)) sent++;
                    }
                }
                catch (COMException)
                {
                    // У этого звукового устройства нет нужной топологии — пропускаем.
                }
                finally
                {
                    Marshal.ReleaseComObject(endpoint);
                }
            }
            Marshal.ReleaseComObject(collection);
        }
        finally
        {
            Marshal.ReleaseComObject(enumerator);
        }
        return sent;
    }

    private static bool TryGetBluetoothFilter(IMMDevice endpoint, out IPart part, out string filterId)
    {
        part = null!;
        filterId = "";
        var topologyIid = typeof(IDeviceTopology).GUID;
        if (endpoint.Activate(ref topologyIid, ClsctxAll, IntPtr.Zero, out var topologyObject) != 0) return false;
        var topology = (IDeviceTopology)topologyObject;
        if (topology.GetConnector(0, out var connector) != 0) return false;
        // Разъём звукового устройства соединён с разъёмом фильтра драйвера Bluetooth.
        if (connector.GetConnectedTo(out var other) != 0) return false;
        part = (IPart)other;
        if (part.GetTopologyObject(out var filterTopology) != 0) return false;
        return filterTopology.GetDeviceId(out filterId) == 0;
    }

    private static bool SendProperty(IPart part, uint propertyId)
    {
        var ksControlIid = typeof(IKsControl).GUID;
        if (part.Activate(ClsctxInprocServer, ref ksControlIid, out var ksObject) != 0) return false;
        var ks = (IKsControl)ksObject;
        var property = new KsProperty { Set = KsPropSetIdBtAudio, Id = propertyId, Flags = KsPropertyTypeGet };
        int hr = ks.KsProperty(ref property, (uint)Marshal.SizeOf<KsProperty>(), IntPtr.Zero, 0, out _);
        Marshal.ReleaseComObject(ks);
        return hr == 0;
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
