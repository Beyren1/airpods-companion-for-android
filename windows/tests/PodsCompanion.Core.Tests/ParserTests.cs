using PodsCompanion.Core.Advertising;
using PodsCompanion.Core.Bluetooth;
using PodsCompanion.Core.Switching;
using Xunit;

namespace PodsCompanion.Core.Tests;

/// <summary>Те же реальные пакеты, что в тестах Android-приложения (RealDumpsTest.kt).</summary>
public class ParserTests
{
    private static ProximityPairingMessage Parse(string hex) =>
        ProximityPairingParser.Parse(Convert.FromHexString(hex.Replace(" ", "")))!;

    [Fact]
    public void AirPods4Anc_BothInEars()
    {
        var msg = Parse("07 19 01 1B 20 2B AA 8F 10 00 05 FF 16 AA 81 5B 82 AC 9D 48 35 32 15 4C 62 EF 55");
        Assert.Equal("AirPods 4 (ANC)", msg.Model?.DisplayName);
        Assert.Equal(new PodState(100, false, true), msg.Left);
        Assert.Equal(new PodState(100, false, true), msg.Right);
        Assert.Null(msg.CaseBattery);
        Assert.True(msg.Worn);
    }

    [Fact]
    public void AirPods4Anc_LeftInOpenCaseCharging()
    {
        var msg = Parse("07 19 01 1B 20 13 9A AF 11 00 04 36 17 ED 81 1D 3D F5 44 4A 97 4D 85 D9 19 93 8A");
        Assert.False(msg.PrimaryIsLeft);
        Assert.Equal(new PodState(90, true, false, true), msg.Left);
        Assert.Equal(new PodState(100, false, true), msg.Right);
    }

    [Fact]
    public void AirPods4Anc_RightInCase_CaseBattery()
    {
        var msg = Parse("07 19 01 1B 20 53 9A 95 3A 00 04 25 2D 6E 55 47 30 EC 44 3F 48 99 E3 6D F8 D6 4F");
        Assert.Equal(new PodState(100, true, false, true), msg.Right);
        Assert.Equal(new PodState(90, false, false), msg.Left);
        Assert.Equal(50, msg.CaseBattery);
    }

    [Fact]
    public void AirPodsMax_OnAndOffHead()
    {
        var on = Parse("07 19 01 1F 20 2B 0A 80 04 56 44 6D 43 28 CB 88 F3 EE EA 7D 1C 82 BE C6 F4 1E B7");
        Assert.Equal(PodsKind.Single, on.Model?.Kind);
        Assert.Equal(new PodState(100, false, true), on.Primary);
        var off = Parse("07 19 01 1F 20 01 09 80 04 56 44 3E 0B 61 8E 7D D8 92 72 35 C2 41 8A CA 5E FE 5E");
        Assert.Equal(new PodState(90, false, false), off.Primary);
        Assert.False(off.Worn);
    }

    [Fact]
    public void NotProximityPairing_ReturnsNull()
    {
        Assert.Null(ProximityPairingParser.Parse(Convert.FromHexString("1005031C")));
    }

    [Theory]
    [InlineData("AirPods", "AirPods 4 (ANC)", true)]
    [InlineData("AirPods", "AirPods Max (USB-C)", false)]
    [InlineData("AirPods Max", "AirPods Max (USB-C)", true)]
    [InlineData("AirPods Max", "AirPods Pro 2", false)]
    [InlineData("AirPods Pro Beyren", "AirPods Pro 2", true)]
    public void NameMatcher_UsesProductLine(string name, string model, bool expected)
    {
        var models = NameMatcher.ModelsForName(name);
        Assert.Equal(expected, models.Any(m => m.DisplayName == model));
    }

    [Fact]
    public void NameMatcher_IgnoresOtherDevices()
    {
        Assert.False(NameMatcher.LooksLikePods("Logitech Mouse"));
    }

    [Fact]
    public void EarPolicy_PausesOnRemovalAndResumesOnReturn()
    {
        var both = Parse("07 19 01 1B 20 2B AA 8F 10 00 05 FF 16 AA 81 5B 82 AC 9D 48 35 32 15 4C 62 EF 55");
        var one = Parse("07 19 01 1B 20 13 9A AF 11 00 04 36 17 ED 81 1D 3D F5 44 4A 97 4D 85 D9 19 93 8A");
        var policy = new EarPolicy();
        Assert.Equal(EarAction.None, policy.OnMessage(both));
        Assert.Equal(EarAction.Pause, policy.OnMessage(one));
        Assert.Equal(EarAction.None, policy.OnMessage(one));
        Assert.Equal(EarAction.Resume, policy.OnMessage(both));
    }

    [Fact]
    public void NearbyTracker_PicksStrongestFreshOfModel()
    {
        var msg = Parse("07 19 01 1F 20 2B 0A 80 04 56 44 6D 43 28 CB 88 F3 EE EA 7D 1C 82 BE C6 F4 1E B7");
        var max = new HashSet<PodsModel> { msg.Model! };
        var t = new NearbyTracker();
        var now = DateTime.UtcNow;
        t.OnPacket(1, msg, -70, now);
        t.OnPacket(2, msg, -50, now - TimeSpan.FromSeconds(30));
        t.OnPacket(3, msg, -90, now);
        Assert.Equal(1UL, t.Best(max, now)?.Address);
    }

    [Fact]
    public void AudioFilterPath_MatchesAddress()
    {
        const string path = @"\\?\{1}.\BTHENUM#{0000110b-0000-1000-8000-00805f9b34fb}_VID&0001004c_PID&2014#8&2a3e&0&AC90856D3A8E_C00000000#{6994ad04-93ef-11d0-a3cc-00a0c9223196}\a2dp";
        Assert.True(BluetoothAudioControl.IsFilterOf(path, "AC90856D3A8E"));
        Assert.False(BluetoothAudioControl.IsFilterOf(path, "AC90856D3A8F"));
        Assert.False(BluetoothAudioControl.IsFilterOf(@"\\?\HDAUDIO#FUNC_01", "AC90856D3A8E"));
        Assert.True(BluetoothAudioControl.IsBluetoothFilter(@"{2}.\\?\bthhfenum#{0000111e-0000-1000-8000-00805f9b34fb}_vid&0001004c"));
    }

    [Theory]
    [InlineData("Наушники (AirPods Max)", "AirPods Max", true)]
    [InlineData("Headset (AirPods Max Hands-Free)", "AirPods Max", true)]
    [InlineData("Наушники (AirPods Max 2)", "AirPods Max", false)]
    [InlineData("Динамики (Realtek(R) Audio)", "AirPods Max", false)]
    public void EndpointName_MatchesPodsName(string endpoint, string pods, bool expected)
    {
        Assert.Equal(expected, BluetoothAudioControl.NameMatches(endpoint, pods));
    }

    [Fact]
    public void PhoneSignal_SameBytesAsPhone()
    {
        // Тот же пример в PcSignalTest.kt на телефоне.
        Assert.Equal("70:F9:4A:9D:33:92", PhoneSignal.AndroidAddress(0x70F94A9D3392));
        Assert.Equal("5043535701 01 E5D3F516 1234".Replace(" ", ""), Convert.ToHexString(PhoneSignal.Payload(0x70F94A9D3392, 0x1234)));
    }

    [Fact]
    public void LinkMessage_SameTextAsPhone()
    {
        // Та же строка в LinkMessageTest.kt на телефоне.
        const string text = "PCLINK 1 hello 3f9a12c0 pc e5d3f516:1,0a0b0c0d:0 DESKTOP ASUS";
        var message = LinkMessage.Parse(text)!;
        Assert.Equal(LinkType.Hello, message.Type);
        Assert.Equal(LinkKind.Pc, message.Kind);
        Assert.Equal([new LinkPods("e5d3f516", true), new LinkPods("0a0b0c0d", false)], message.Pods);
        Assert.Equal("DESKTOP ASUS", message.Name);
        Assert.Equal(text, message.Encode());
        Assert.Equal("e5d3f516", LinkMessage.HashOf(0x70F94A9D3392));
        Assert.Null(LinkMessage.Parse("PCLINK 1 jump 1 pc - X"));
        Assert.Equal("PCLINK 1 hi 1 phone - Pixel 9", new LinkMessage(LinkType.Hi, "1", LinkKind.Phone, [], "Pixel 9").Encode());
    }
}
