#include <stdio.h>
#include <stdlib.h>
#include <fcntl.h>
#include <sys/ioctl.h>
#include <sys/ioctl.h>      // IOCTL
#include <dirent.h>

enum {
        CONTROL_ERR = -1,
        CONTROL_TCS = 0,
        CONTROL_GPS = 1,
        CONTROL_3GP = 2,
        CONTROL_GSENSOR = 3,
        CONTROL_VRB = 4,  //control vibration
        CONTROL_GET_SENSOR_NAME = 5, //µÃµ½µ±Ç°´«¸ÐÆ÷µÄÃû×Ö Èç:lsm303dlh, bma220
};
enum {
        STATE_ON        = 0,
        STATE_OFF       = 1,
        STATE_RESUME    = 2,
        STATE_SUSPEND   = 3,
        STATE_TRIGGER   = 4,
        STATE_GET      = 5,
        STATE_CALI      = 6,
        STATE_MANUAL   = 10,    //ÊÖ¶¯GPIO¿ØÖÆ£¬ÐèÒª½âÎö×Ö·û´®
        STATE_SENSOR_TYPE = 11,  //GSENOSRµÄÐÍºÅ£¬Èçlsm303dlh, bma220
        STATE_SENSOR_RANGE = 12, //ÔÚÐ£ÕýÊ±ÓÃµ½µÄ·¶Î§

        //...
        STATE_TCS_GETPARAM = 100,
        STATE_TCS_RECALIBRATE=101,
        STATE_TCS_GETSAMPLE = 102,
        STATE_TCS_NEXTPOINTER=103,
        STATE_TCS_DONE = 104,
        STATE_TCS_NORMAL = 105,
};

#define	IOCTL_MAKE(t, c)	0xfcde0000 | ((t)<<8) | ((c) & 0xff)
#define IOCTL_3GP_OFF           IOCTL_MAKE(CONTROL_3GP, STATE_OFF)
#define IOCTL_3GP_ON            IOCTL_MAKE(CONTROL_3GP, STATE_ON)

int internal_modem_power(int on)
{
	int fd_ioctl = -1;
	fd_ioctl = open("/dev/io_ctl", O_RDWR);	//io control
	if(fd_ioctl >= 0) {
		if(on) {
			//turn on min PCIE power
			ioctl(fd_ioctl,  IOCTL_3GP_ON);
		} else {
			ioctl(fd_ioctl, IOCTL_3GP_OFF);
		}
		close(fd_ioctl);
	}
    return (fd_ioctl>=0)?0:-1;
}

static void read_content(const char *filename, char *buf, size_t bufsize)
{
	int fd;
	memset(buf, 0, bufsize);
	fd = open(filename, O_RDONLY);
	if(fd >= 0) {
		read(fd, buf, bufsize);
		close(fd);
	}
}

#define	GGGSUPORTTYPE_POWER	(1<<0)
#define	GGGSUPORTTYPE_VOICE	(1<<1)
int is_support_voice()
{
    int flag = 0;
    char buf[64];
    read_content("/sys/class/touchkey/touchkey/GGGType", buf, sizeof(buf));
    //printf("%s\n", buf);
    if(buf[2]!='0') flag |= GGGSUPORTTYPE_POWER;
    if(buf[3]!='0') flag|= GGGSUPORTTYPE_VOICE;
    return flag;
}

static int read_usb_id(const char *filename)
{
    char buf[8];
    read_content(filename, buf, sizeof(buf));
    //printf("%s, %s\n", filename, buf);
    return strtol(buf, NULL, 16);
}

int find_usb_hub()
{
    const char *path = "/sys/bus/usb/devices/";
    int ret = 0;
    DIR *dp = NULL;
    struct dirent *dirp;
    char filename[260];
    int idVendor, idProduct;
    if( (dp = opendir(path)) == NULL ) {
        goto Exit;
    }
    while( (dirp = readdir(dp)) != NULL ) {
        if(strcmp(".", dirp->d_name) == 0 ||  strcmp("..", dirp->d_name) == 0)  
        continue;  
        sprintf(filename, "%s%s/idVendor", path, dirp->d_name);
        idVendor = read_usb_id(filename);
        sprintf(filename, "%s%s/idProduct", path, dirp->d_name);
        idProduct = read_usb_id(filename);
        if( (idVendor == 0x05e3) && (idProduct == 0x0608) ) {
            ret = 1;
            goto Exit;
        }
    }

Exit:
    if(dp != NULL)
    closedir(dp);
    return ret;
}


