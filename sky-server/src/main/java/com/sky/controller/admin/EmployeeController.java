package com.sky.controller.admin;

import com.sky.constant.JwtClaimsConstant;
import com.sky.dto.EmployeeDTO;
import com.sky.dto.EmployeeLoginDTO;
import com.sky.dto.EmployeePageQueryDTO;
import com.sky.entity.Employee;
import com.sky.utils.properties.JwtProperties;
import com.sky.result.PageResult;
import com.sky.result.Result;
import com.sky.service.EmployeeService;
import com.sky.utils.JwtUtil;
import com.sky.vo.EmployeeLoginVO;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;

/**
 * 闂備礁鎲＄粙鎺楁晪婵炴潙鍚嬮崝妤冨垝婵犳碍鏅柛鏇ㄥ墮閳?
 */
@RestController
@RequestMapping("/admin/employee")
@Slf4j
@Api(tags = "employee api")
public class EmployeeController {

    @Autowired
    private EmployeeService employeeService;
    @Autowired
    private JwtProperties jwtProperties;

    /**
     * 闂備浇鐨崱鈺佹缂?
     *
     * @param employeeLoginDTO
     * @return
     */
    @PostMapping("/login")
    @ApiOperation(value = "employee login")
    public Result<EmployeeLoginVO> login(@RequestBody EmployeeLoginDTO employeeLoginDTO) {
        log.info("employee login: {}", employeeLoginDTO);

        Employee employee = employeeService.login(employeeLoginDTO);

        //闂備浇鐨崱鈺佹缂傚倸绉寸粔褰掔嵁鐎ｎ喖绠涙い鎺嗗亾妞ゎ偆濞€閺屾稑顫濋鈧崵杈╃磼鏉堛劎绠為柟顔荤矙閹垻绮欑捄銊ゆ捣jwt濠电偛顕慨浼村磹濡や焦娅?
        Map<String, Object> claims = new HashMap<>();
        claims.put(JwtClaimsConstant.EMP_ID, employee.getId());
        String token = JwtUtil.createJWT(
                jwtProperties.getAdminSecretKey(),
                jwtProperties.getAdminTtl(),
                claims);

        EmployeeLoginVO employeeLoginVO = EmployeeLoginVO.builder()
                .id(employee.getId())
                .userName(employee.getUsername())
                .name(employee.getName())
                .token(token)
                .build();

        return Result.success(employeeLoginVO);
    }

    /**
     * 闂傚倷绶￠崑鈧柛瀣崌閺?
     *
     * @return
     */
    @PostMapping("/logout")
    @ApiOperation("employee logout")
    public Result<String> logout() {
        return Result.success();
    }

    /**
     * 闂備礁鎼崐鐟邦熆濮椻偓璺柛鎰靛枛瀹告繃淇婇婵囥€冪紒?
     * @param employeeDTO
     * @return
     */
    @PostMapping
    @ApiOperation("add employee")
   public Result add(@RequestBody EmployeeDTO employeeDTO){
        log.info("闂備礁鎼崐鐟邦熆濮椻偓璺柛鎰靛枛瀹告繃淇婇婵囥€冪紒鎲嬬秮閺屻劌鈽夊▎妯哄П}", employeeDTO);
        employeeService.add(employeeDTO);
        return Result.success();
   }

    /**
     * 闂備礁鎲＄敮鎺懳涘┑瀣ュ鑸靛姇閽冪喖鏌曟径妯煎帥闁?
     * @param employeePageQueryDTO
     * @return
     */
    @GetMapping("/page")
    @ApiOperation("employee page query")
    public Result<PageResult> pageQuery(EmployeePageQueryDTO employeePageQueryDTO){
        log.info("闂備礁鎲＄敮鎺懳涘┑瀣ュ鑸靛姇閽冪喖鏌曟径妯煎帥闁搞倕瀚伴弻銊モ槈濞嗘ê濮眪",employeePageQueryDTO);
        PageResult pageResult = employeeService.pageQuery(employeePageQueryDTO);
        return Result.success(pageResult);
    }
    /**
     * 闂備礁鎲￠崙褰掑垂閹惰棄鏋侀柕鍫濇川閻霉閿濆洤鍔嬮柡鍡楃箻閺屾稑鈽夊▍顓т簼鐎靛ジ骞囬钘夘伕闂佹寧娲嶉崑鎾舵喐?
     * @param status
     * @param id
     * @return
     */
    @PostMapping("/status/{status}")
    @ApiOperation("enable or disable employee")
    public Result startOrStop(@PathVariable Integer status,Long id){ //闂佽娴烽弫濠氬焵椤掍胶銆掗柤褰掔畺閺岋紕浠︾拠鎻掑Г濡炪倖娲橀〃鍫ユ偖妤ｅ啯鍤掗柕鍫濇閺嗙娀姊洪悷鎵虎缂佸纾槐鐐哄籍閸噥姊块梺閫炲苯澧伴柟鑼閹峰懐绮欓幐搴㈢彃Result闂備礁鎲￠懝楣冩煀閿濆拋鐒藉ù鍏兼綑缁€澶愭煟濡じ鍚柣鐔稿姈缁绘稒寰勭€ｎ兘妲堥梺?>闂備焦鎮堕崕铏叏瀹曞洦瀚婚柣鏃傚帶閽冪喖鏌曟径妯煎帥闁搞倕瀚槐鎺楊敃閵夈儱鎯炲┑鐐茬墛閸ㄥ潡鐛▎蹇ｅ悑闁告洦鍘鹃埢鏇㈡⒒閸屾碍绀岄柛瀣崌閹兘寮村鍗炲婵犵绱曢悵顡a闂備浇妗ㄩ懗鑸垫櫠濡も偓閻ｅ灚鎷呯憴鍕唉闂佸搫鍊圭€笛呯矆閳ь剛绱撻崒娆戝妽闁荤喆鍎查弲鍫曟偐閻㈤潧宕ュ銈嗙墦閸婃绮堟径鎰厵缁剧増锚娴滅偓绻涚€电袨闁稿酣娼ч妴鎺楀礈娴ｇ懓鏆繛杈剧悼椤牓锝為敐鍛闁瑰墽顒查弨鑽ょ棯椤撱垻鐣虹€?
        //闂備礁鍚嬮崕鎶藉床閼艰翰浜归柛銉墮缁€鍫⑩偓骞垮劚濞村倹瀵奸崒姣綊鎳栭埡浣囷絾绻涢懖鈺傛毈闁诡垰鍟村畷鐔碱敊閸撗冩暥闁诲骸鐏氬妯尖偓姘煎墮椤曪綁顢楅崟顐ｇ€柣銏╁灱閸犳氨绮堟径鎰拻闁搞儻绲芥禍楣冩煟閻斿憡纾荤紒鈧担瑙勫弿闁绘劕顕埢鏂款熆閸撲胶銈癮thVariable闂備焦瀵х粙鎴︽儗娓氣偓閸┾偓妞ゆ巻鍋撴い鎾剁闂備礁鎼€氱兘宕归弶鍖¤€块柟缁㈠枛闁裤倝鏌嶈閸撶喎顕ｉ崐鐔虹杸閹艰揪绲块崐鐐烘⒑閸涘﹦鎳冮柛濠囶棑缁厽寰勯幇顓ф⒖闂侀€炲苯澧伴柟鑼閹峰懐绮欏▎鐐@PathVariable
        log.info("闂備礁鎲￠崙褰掑垂閹惰棄鏋侀柕鍫濇川閻霉閿濆洤鍔嬮柡鍡楃箻閺屾稑鈽夊▍顓т簼鐎靛ジ骞囬钘夘伕闂佹寧娲嶉崑鎾舵喐閺夋寧鍋ラ柡浣哥Т椤垻缂?{}",status,id);
        employeeService.startOrStop(status,id);//闂備礁鎲￠懝鎯归悜钘夌閹肩补妲呭浼存煏婢跺棙娅撻柍褜鍓欓崯浼村焵椤掑喚娼愰柣顓у枤缁?
        return Result.success();
    }
    /**
     * 闂備礁鎼粔鐑斤綖婢跺﹦鏆ゅ☉鏃傛暊闂備礁鎼悮顐﹀磿閹绢噮鏁嬫俊銈呮噹瀹告繃淇婇婵囥€冪紒鎲嬬到鑿愰柛銉到婢ф彃霉?
     * @param id
     * @return
     */
    @GetMapping("/{id}")
    @ApiOperation("get employee by id")
    public Result<Employee> getById(@PathVariable Long id){ //闂備礁鎼悮顐﹀磿閹绢噮鏁嬫俊銈呮噹缁€澶愭煟閺冨浂鍤欓柛妯绘尦閺屻劌鈽夊Ο鑲╁姰濡炪倐鏅粻鎾崇暦濡　鏋庨幖杈剧秵娴煎洦绻涢幋鐐存儎濞存粍绻勫☉鍨偅閸愩劌鐎?
        Employee employee = employeeService.getById(id);
        return Result.success(employee);
    }

    /**
     * 缂傚倸鍊搁崐褰掓偋閻愬灚顐芥い鎰剁畱瀹告繃淇婇婵囥€冪紒鎲嬬到鑿愰柛銉到婢ф彃霉?
     * @param employeeDTO
     * @return
     */
    @PutMapping
    @ApiOperation("update employee")
    public Result update(@RequestBody EmployeeDTO employeeDTO){ //闂傚倸鍊搁悧蹇涘磻閻愮儤鍋傞柨娑樺鐎氭岸鏌曡箛濠傚⒉闁诡垱鐟ラ湁闁挎繂妫涢悳鑽ょ磼鏉堛劎绀€esult闂佽绻愮换鎰亹婢跺瞼绠斿鑸靛姈閸嬨劑鏌曟繛鍨姎妞ゎ偓绲剧换娑欏緞鐎ｎ兘妲堥梺璇查叄閺€杈╃矉?
        log.info("update employee: {}", employeeDTO);
        employeeService.update(employeeDTO);
        return Result.success();
    }
}
